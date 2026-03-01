# Istio Service Group Operator

基于 Istio 的 Kubernetes Operator，实现微服务环境隔离与 fallback 路由。

## 核心特性

- **环境隔离**：ServiceEnv 定义环境，Deployment 通过标签声明加入
- **Fallback**：每个 ServiceEnv 可独立配置 `fallbackEnv`，本环境无服务时路由到 fallback subset
- **全链路保持**：请求 header `x-service-env` 贯穿调用链
- **自动化**：Operator 自动创建 VirtualService 和 DestinationRule

## 工作原理

| 资源变化 | 触发 |
|---------|------|
| Deployment(env=E) | ServiceEnv(envName=E)、ServiceEnv(fallbackEnv=E) |

- 各 ServiceEnv 只处理本环境 Deployment，创建 DR + VS
- 若 `spec.fallbackEnv` 存在，为「fallback 有、本环境无」的服务创建 fallback VS；fallback 目标无 deployment 则 503

## 安装

```bash
helm install serviceenv-operator ./helm/serviceenv-operator -n serviceenv-operator --create-namespace
```

## 使用

1. **创建环境**（按需配置 fallbackEnv）

```yaml
# prod、base 无 fallback
spec:
  envName: prod
  enabled: true

# dev fallback 到 base
spec:
  envName: dev
  fallbackEnv: base
  enabled: true
```

2. **服务加入环境**：Deployment 标签 `serviceenv.zaeyi.com/env: dev`

3. **请求**：携带 header `x-service-env: dev`

## 路由逻辑

```
x-service-env: dev → 匹配 dev subset
  ├─ 有 dev deployment → 路由到 dev
  └─ 无 dev，有 fallbackEnv(base) → 路由到 base
       └─ 无 base → 503
```

## 限制

- 依赖 Istio
- 需传递 `x-service-env` header
- 同一 namespace 内环境名唯一

## 参考

- [Istio](https://istio.io/latest/docs/)
- [Java Operator SDK](https://javaoperatorsdk.io/)
