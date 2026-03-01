# Istio Service Group Operator

基于 Istio 的 Kubernetes Operator，实现微服务环境隔离与 namespace 级兜底 fallback 路由。

## 核心特性

- **环境隔离**：ServiceEnv 定义环境，Deployment 通过标签声明加入
- **App CRD**：`serviceenv.zaeyi.com/app`，VS 和 DR 归属于 App，app 名称从 `app.kubernetes.io/name` 读取
- **Namespace 级 Fallback**：通过 namespace 注解配置兜底 env，无匹配路由或 subset 无端点时 fallback
- **全链路保持**：请求 header `x-service-env` 贯穿调用链
- **自动化**：Operator 自动创建 VirtualService、DestinationRule、EnvoyFilter

## 架构设计

| 资源 | 策略 | 说明 |
|------|------|------|
| **VirtualService** | 每 service 一个 | 按 env 的路由 + 兜底 catch-all |
| **DestinationRule** | 每 service 一个 | 包含该 service 在所有 env 的 subsets |

符合 K8s/Istio 哲学：一个逻辑实体一个资源，避免多 VS 合并顺序问题。

## 工作原理

| Reconciler | Primary | 职责 |
|------------|---------|------|
| **AppReconciler** | App | VS/DR 唯一写入者，按 app 增量 reconcile |
| **ServiceEnvReconciler** | ServiceEnv | 仅更新 ServiceEnv status，不写 VS/DR |

App 需用户显式创建，Deployment 不创建 App，生命周期分离。

- **App CRD**：`serviceenv.zaeyi.com/app`，`spec.appName` 必须与 Deployment 的 `app.kubernetes.io/name` 一致；`metadata.name` 为 App 自有名称
- **VS/DR 归属**：通过 ownerReference 归属于 App，App 删除时自动级联删除

## 安装

```bash
helm install serviceenv-operator ./helm/serviceenv-operator -n serviceenv-operator --create-namespace
```

## 使用

### 1. 配置 Namespace 兜底 Fallback

在 namespace 上添加注解，指定无匹配路由时的兜底 env：

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: my-namespace
  annotations:
    serviceenv.zaeyi.com/fallback-env: base
```

- 有注解：无匹配路由时 → 路由到 fallback subset
- 无注解：不配置 fallback，无匹配时可能 503
- Subset 有部署但无健康 Pod 时，按 Istio 默认行为返回错误

**修改注解后**：需触发 reconcile（如 `kubectl annotate serviceenv xxx --overwrite` 或重启 Operator）。

### 2. 创建环境

```yaml
apiVersion: serviceenv.zaeyi.com/v1
kind: ServiceEnv
metadata:
  name: prod
  namespace: my-namespace
spec:
  envName: prod
  enabled: true
---
apiVersion: serviceenv.zaeyi.com/v1
kind: ServiceEnv
metadata:
  name: base
  namespace: my-namespace
spec:
  envName: base
  enabled: true
---
apiVersion: serviceenv.zaeyi.com/v1
kind: ServiceEnv
metadata:
  name: dev
  namespace: my-namespace
spec:
  envName: dev
  enabled: true
```

### 3. 创建 App

App CR 需先于 Deployment 创建，`spec.appName` 必须与 Deployment 的 `app.kubernetes.io/name` 一致：

```yaml
apiVersion: serviceenv.zaeyi.com/v1
kind: App
metadata:
  name: my-service
  namespace: my-namespace
spec:
  appName: my-service
```

### 4. 服务加入环境

Deployment 标签 `serviceenv.zaeyi.com/env: <envName>`，并设置 `app.kubernetes.io/name` 与 App 的 `spec.appName` 一致：

```yaml
metadata:
  labels:
    serviceenv.zaeyi.com/env: dev
spec:
  template:
    metadata:
      labels:
        app.kubernetes.io/name: my-service
        serviceenv.zaeyi.com/env: dev
```

### 5. 请求

携带 header `x-service-env: <envName>`。

## 路由逻辑

```
x-service-env: dev → 匹配 dev subset
  ├─ 有 dev deployment → 路由到 dev
  ├─ 无 dev deployment → 走 catch-all → 路由到 fallback (base)
  └─ dev subset 无端点 → EnvoyFilter lb fallback → base
```

## 限制

- 依赖 Istio
- 需传递 `x-service-env` header
- 同一 namespace 内环境名唯一

## 参考

- [Istio](https://istio.io/latest/docs/)
- [Java Operator SDK](https://javaoperatorsdk.io/)
