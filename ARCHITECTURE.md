# 架构文档

## 概述

本项目是一个 Kubernetes Operator，管理多环境下的 Istio 流量路由。用户通过两个自定义资源（CRD）描述意图，Operator 自动生成并维护对应的 Istio VirtualService 和 DestinationRule。

**核心流量模型**：每个请求携带 HTTP Header `x-service-env: <envName>`，流量被路由到对应环境的 Deployment。未匹配的请求回落到 namespace 配置的 fallback env。

---

## 核心资源

### App

代表一个需要多环境路由的服务。

```yaml
apiVersion: serviceenv.zaeyi.com/v1
kind: App
metadata:
  name: my-service        # 通常与 K8s Service 同名
spec:
  appName: my-service     # 必须与 Deployment Pod template 的 app.kubernetes.io/name 标签一致
```

`App` 是 `VirtualService` 和 `DestinationRule` 的 owner，这两个 Istio 资源跟随 `App` 的生命周期。

### ServiceEnv

代表一个部署环境（如 `dev`、`staging`、`feature-xyz`）。

```yaml
apiVersion: serviceenv.zaeyi.com/v1
kind: ServiceEnv
metadata:
  name: dev               # 约定：metadata.name 必须等于 spec.envName
spec:
  envName: dev
  enabled: true
```

**重要约定**：`metadata.name == spec.envName`，Operator 用 env 名直接定位对应的 `ServiceEnv` 对象。

### Deployment（用户侧）

用户在 Deployment 上打标签，声明该 Deployment 属于哪个 env：

```yaml
metadata:
  labels:
    serviceenv.zaeyi.com/env: dev         # 属于 dev 环境
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/name: my-service  # app 名称，对应 App.spec.appName
        app.kubernetes.io/version: v1.2.0   # 可选，记录在 ServiceEnv status 中
```

### VirtualService / DestinationRule

由 Operator 自动生成，用户不需要手动创建。

- `DestinationRule`（`<appName>-dr`）：为每个活跃 env 定义一个 subset，按 `serviceenv.zaeyi.com/env` 标签路由
- `VirtualService`（`<appName>-vs`）：为每个活跃 env 生成一条按 Header 匹配的路由规则，末尾添加 fallback 兜底路由

---

## 资源依赖关系

```
Deployment ──(标签关联)──► App ──(owner)──► VirtualService
                                  └──(owner)──► DestinationRule

ServiceEnv ◄──(env 名关联)── Deployment
```

**所有权（ownerReference）**：
- `VirtualService` 和 `DestinationRule` 的 owner 是 `App`，App 删除时自动级联删除
- `ServiceEnv` 与 `App` 之间**没有所有权关系**，两者是独立的平级资源

**依赖方向**（单向，不允许反向）：
- `AppReconciler` 读取 `ServiceEnv`（判断 env 是否启用），但**不写入** `ServiceEnv`
- `ServiceEnvReconciler` 读取 `Deployment`，但**不写入** `App` 或 Istio 资源

---

## 组件职责划分

### AppReconciler

**触发条件**：
- `App` 自身 spec 变更
- 带有 `serviceenv.zaeyi.com/env` 标签的 `Deployment` 增减或变更
- `ServiceEnv` 的 spec 变更（generation 变化，status 变更被过滤）

**协调逻辑**：
1. 从 Deployment 内存索引中找出该 app 的所有 env 标签
2. 过滤掉没有对应 `ServiceEnv` 或已禁用的 env，得到 `activeEnvs`
3. 将 `activeEnvs` 注入 reconcile context，由 `@Workflow` 驱动 VS/DR 更新
4. 更新 `App.status.envs`

**不负责**：`ServiceEnv.status` 的维护

### ServiceEnvReconciler

**触发条件**：
- `ServiceEnv` 自身变更（spec 变更、enabled 开关）
- 带有 `serviceenv.zaeyi.com/env` 标签的 `Deployment` 增减或变更

**协调逻辑**：
1. 若 `enabled=false`，将 status 置为 Disabled
2. 若 `enabled=true`，从 Deployment 内存索引全量扫描该 env 下所有服务
3. 重建 `ServiceEnv.status.services` 列表

**不负责**：VS/DR 的创建，`App.status` 的维护

### DestinationRuleDependentResource / VirtualServiceDependentResource

由 `AppReconciler` 的 `@Workflow` 自动触发，在 `AppReconciler.reconcile()` 之后执行。

从 `AppReconcileContext` 读取已计算好的 `activeEnvs`，构建并 apply 对应的 Istio 资源。

### IstioConfigService

仅提供两个只读查询方法：
- `getFallbackEnvFromNamespace()`：读取 namespace 注解，返回 fallback env 名
- `computeEnvironmentNamesForApp()`：DependentResource 的 fallback 计算路径（正常流程不走此方法）

---

## 事件触发链

### 场景一：新增带 env 标签的 Deployment

```
Deployment 创建
  ├─► AppReconciler（mapper: deploymentToApps → 找到对应 App）
  │     → resolveActiveEnvs → activeEnvs 更新
  │     → @Workflow 触发 VS/DR 更新
  │     → App.status.envs 更新
  │
  └─► ServiceEnvReconciler（mapper: deploymentToServiceEnv → 找到对应 ServiceEnv）
        → buildServiceList → 重建 services 列表
        → ServiceEnv.status.services 更新
```

### 场景二：创建新的 ServiceEnv

```
ServiceEnv 创建
  ├─► ServiceEnvReconciler（primary resource 变更）
  │     → buildServiceList → 全量扫描该 env 下的 Deployment
  │     → ServiceEnv.status.services 更新
  │
  └─► AppReconciler（mapper: serviceEnvToApps → 找到该 env 下所有 App）
        → resolveActiveEnvs → 新 env 加入 activeEnvs
        → @Workflow 触发 VS/DR 更新（新增该 env 的路由规则）
        → App.status.envs 更新
```

### 场景三：禁用 ServiceEnv（enabled: false）

```
ServiceEnv spec 变更（generation 增加）
  ├─► ServiceEnvReconciler
  │     → ServiceEnv.status.phase = Disabled
  │
  └─► AppReconciler（generation 过滤器放行）
        → resolveActiveEnvs → 该 env 被过滤掉
        → @Workflow 触发 VS/DR 更新（移除该 env 的路由规则）
        → App.status.envs 更新（移除该 env）
```

### 场景四：删除 Deployment

```
Deployment 删除
  ├─► AppReconciler
  │     → resolveActiveEnvs → 若该 app 已无该 env 的 Deployment，env 从 activeEnvs 移除
  │     → @Workflow 触发 VS/DR 更新
  │
  └─► ServiceEnvReconciler
        → buildServiceList → 重建（该 app 从列表中消失）
        → ServiceEnv.status.services 更新
```

---

## 内存缓存与索引

Operator 通过 JOSDK 的 `InformerEventSource` 维护资源的本地内存缓存，reconcile 主路径中**不发 K8s API 请求**。

### AppReconciler 中的 Deployment 索引

| 索引名 | key 格式 | 用途 |
|--------|----------|------|
| `namespace-env` | `"namespace#envName"` | ServiceEnv 变更时，找出该 env 下所有有 Deployment 的 App |
| `namespace-app` | `"namespace#appName"` | reconcile 时，O(1) 取出该 app 的所有 env Deployment |

### ServiceEnvReconciler 中的 Deployment 索引

| 索引名 | key 格式 | 用途 |
|--------|----------|------|
| `namespace-env` | `"namespace#envName"` | reconcile 时，O(1) 取出该 env 下所有 Deployment |

### Reconcile Context 数据传递

`AppReconciler` → `DependentResource` 通过 `AppReconcileContext` 传递 `activeEnvs`：

```
AppReconciler.reconcile()
  → AppReconcileContext.putResolvedEnvs(context, activeEnvs)
  → @Workflow 执行
    → DestinationRuleDependentResource.desired()
        → AppReconcileContext.getResolvedEnvs(context)  // 直接读缓存，无 API 请求
    → VirtualServiceDependentResource.desired()
        → AppReconcileContext.getResolvedEnvs(context)  // 同上
```

---

## namespace 配置

在 namespace 上添加注解，配置该 namespace 下所有 App 的 fallback env：

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: my-namespace
  annotations:
    serviceenv.zaeyi.com/fallback-env: base   # 无匹配 Header 时路由到 base env
```

无此注解时，VirtualService 不生成 catch-all 路由（无匹配请求会被 Istio 拒绝）。
