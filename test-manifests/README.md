# ServiceEnv Operator 测试清单

用于验证路由分组隔离和 namespace 级 fallback 功能。所有资源位于 `serviceenv-test` 命名空间，便于一键清理。

**v0.9.0+**：App CR 通过 DependentResource 自动管理 VirtualService / DestinationRule。

## 测试结构

| 资源 | 说明 |
|------|------|
| **00-namespace** | 含 `serviceenv.zaeyi.com/fallback-env: base` 注解 |
| **01-serviceenv** | ServiceEnv CR 实例 |
| **02-services** | httpbin Service |
| **03-deployments** | prod/base/dev 分组 Deployment |
| **04-curl-client** | 集群内 curl 客户端（走 Istio 路由） |
| **05-apps** | App CR（触发 DependentResource 创建 VS / DR） |

| 服务 | 说明 |
|------|------|
| **httpbin** | prod/base/dev 均有部署，验证环境隔离 |

## 一键测试（推荐）

```bash
./run-all.sh
```

执行：部署 → 等待 reconcile（默认 25s）→ 路由验证。

可通过环境变量调整等待时间：`RECONCILE_WAIT=30 ./run-all.sh`

## 分步执行

```bash
./apply.sh
# 等待 Operator reconcile（约 15–25 秒）
./test.sh
```

## 调试：验证 VS/DR

检查 App DependentResource 是否正确创建 VirtualService / DestinationRule：

```bash
./verify-vs-dr.sh
```

## 清理

```bash
./cleanup.sh
```

删除 `serviceenv-test` 命名空间及其中所有资源（ServiceEnv、App、Deployment、Service、Pod、VirtualService、DestinationRule 等）。

**说明**：测试验证 httpbin 的环境隔离（prod/base/dev 按 x-service-env 路由）。`test.sh` 从 curl-client Pod 发起请求，走 Istio 路由。
