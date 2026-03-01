# ServiceEnv Operator 测试清单

用于验证路由分组隔离和 namespace 级 fallback 功能。所有资源位于 `serviceenv-test` 命名空间，便于一键清理。

## 测试结构

| 资源 | 说明 |
|------|------|
| **00-namespace** | 含 `serviceenv.zaeyi.com/fallback-env: base` 注解 |
| **httpbin** | prod/base/dev 均有部署，验证环境隔离 |
| **fallback-target** | 仅 prod/base，无 dev，验证 dev fallback 到 base |

## 部署

```bash
./apply.sh
```

## 验证

等待 Operator 完成 reconcile 后（约 10–30 秒），执行：

```bash
./test.sh
```

**预期结果：**
1. **环境隔离**：`x-service-env: prod/base/dev` 分别路由到对应 subset
2. **Fallback**：`x-service-env: dev` 访问 fallback-target 时，路由到 base subset

## 清理

```bash
./cleanup.sh
```

删除 `serviceenv-test` 命名空间及其中所有资源（ServiceEnv、Deployment、Service、Pod、VirtualService、DestinationRule 等）。

**说明**：`test.sh` 使用 httpbin Pod 内 Python 发起请求，无需额外镜像。
