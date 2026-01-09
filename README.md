# Istio Service Group Operator

基于Istio的测试环境路由工具和分组路由工具，通过Kubernetes Operator实现微服务的环境隔离和智能路由。

## 📋 项目概述

本项目是一个Kubernetes Operator，它引入了`ServiceEnv`自定义资源（CRD），用于在Istio服务网格中实现灵活的环境隔离和路由管理。主要解决多租户、多环境场景下的服务路由和流量隔离问题。

### 核心特性

- **环境隔离**：通过ServiceEnv资源定义独立的服务环境
- **智能Fallback**：支持环境级联，当服务在当前环境不存在时自动路由到兜底环境
- **全链路环境保持**：请求在整个调用链中保持环境标识，不会因为fallback而改变环境上下文
- **声明式加入**：服务通过标签声明加入环境，无需修改ServiceEnv配置
- **自动化路由配置**：Operator自动创建和管理Istio的VirtualService和DestinationRule
- **Native镜像支持**：使用GraalVM编译为原生二进制，快速启动和低内存占用

## 🏗️ 架构设计

### 技术栈

- **运行时**：GraalVM JDK 25
- **框架**：Spring Boot 4.0.1
- **Operator SDK**：Java Operator SDK (JOSDK) 6.3.1
- **构建工具**：Gradle 最新版
- **服务网格**：Istio
- **容器运行时**：Kubernetes

### 核心组件

```
┌─────────────────────────────────────────────────────────────┐
│                  Istio Service Group Operator                │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │           ServiceEnvReconciler                       │   │
│  │  (监听ServiceEnv资源变化)                            │   │
│  └──────────────┬──────────────────────────────────────┘   │
│                 │                                            │
│                 ├─────► ServiceDiscoveryService             │
│                 │       (发现环境中的服务)                  │
│                 │                                            │
│                 └─────► IstioConfigService                  │
│                         (配置VirtualService/DestinationRule)│
│                                                               │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  DeploymentReconciler / PodReconciler                │   │
│  │  (监听Pod/Deployment变化，触发环境更新)              │   │
│  └─────────────────────────────────────────────────────┘   │
│                                                               │
└─────────────────────────────────────────────────────────────┘
                        │
                        ▼
        ┌───────────────────────────────┐
        │      Kubernetes & Istio        │
        ├───────────────────────────────┤
        │  • ServiceEnv (CRD)            │
        │  • VirtualService              │
        │  • DestinationRule             │
        │  • Pod / Deployment            │
        └───────────────────────────────┘
```

### 工作原理

1. **环境定义**：用户创建`ServiceEnv`资源，定义环境名称和fallback环境
2. **服务加入**：Pod/Deployment通过标签`serviceenv.zaeyi.com/service-env=<env-name>`声明加入环境
3. **自动发现**：Operator监听Pod/Deployment变化，自动发现环境中的服务
4. **路由配置**：Operator为每个服务创建Istio路由规则：
   - **DestinationRule**：定义服务的环境子集（subset）
   - **VirtualService**：定义路由规则，实现环境隔离和fallback逻辑
5. **流量路由**：请求携带`x-service-env`头部，Istio根据规则路由到对应环境的服务实例

### 路由逻辑

```
请求 (x-service-env: dev)
    │
    ▼
服务A (dev环境)
    │
    ├─调用→ 服务B
    │       │
    │       ├─存在dev环境实例？
    │       │   ├─是 → 路由到服务B (dev)
    │       │   └─否 → 路由到服务B (fallback环境，如prod)
    │       │          但保持x-service-env: dev
    │       │
    │       └─调用→ 服务C
    │               ├─存在dev环境实例？
    │               │   ├─是 → 路由到服务C (dev)
    │               │   └─否 → 路由到服务C (fallback环境)
    │               │          仍然保持x-service-env: dev
    │               ...
    ...
```

**关键设计要点**：
- 环境标识在整个调用链中保持不变
- 路由决策在请求前完成，不是错误后的兜底
- Fallback是透明的，不改变业务逻辑的环境上下文

## 📦 安装部署

### 前置条件

- Kubernetes集群 (1.24+)
- Istio已安装 (1.18+)
- kubectl命令行工具
- 集群管理员权限

### 快速开始

1. **安装CRD**

```bash
kubectl apply -f k8s/crd.yaml
```

2. **安装RBAC**

```bash
kubectl apply -f k8s/rbac.yaml
```

3. **部署Operator**

```bash
kubectl apply -f k8s/deployment.yaml
```

4. **验证安装**

```bash
# 检查Operator运行状态
kubectl get pods -l app=serviceenv-operator

# 检查CRD
kubectl get crd serviceenvs.serviceenv.zaeyi.com
```

## 🔨 本地开发

### 环境准备

1. 安装GraalVM JDK 25
2. 安装Gradle
3. 配置Kubernetes集群访问

### 构建项目

```bash
# 标准构建
./gradlew build

# 构建Native镜像
./gradlew nativeCompile

# 构建Docker镜像
docker build -t serviceenv-operator:latest .
```

### 本地运行

```bash
# 确保有kubeconfig访问集群
export KUBECONFIG=~/.kube/config

# 运行Operator
./gradlew bootRun
```

## 🎯 使用方式

详细使用示例请参考 [EXAMPLES.md](EXAMPLES.md)

### 基本用法

1. **创建环境**

```yaml
apiVersion: serviceenv.zaeyi.com/v1
kind: ServiceEnv
metadata:
  name: dev-env
  namespace: default
spec:
  envName: dev
  fallbackEnv: prod
  description: "开发环境"
  enabled: true
```

2. **服务加入环境**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: service-a
  labels:
    app: service-a
    serviceenv.zaeyi.com/service-env: dev  # 声明加入dev环境
    version: v1
spec:
  replicas: 2
  selector:
    matchLabels:
      app: service-a
      version: v1
  template:
    metadata:
      labels:
        app: service-a
        version: v1
        serviceenv.zaeyi.com/service-env: dev
    spec:
      containers:
        - name: service-a
          image: your-service:latest
```

3. **发起请求**

请求需要携带环境标识头部：

```bash
curl -H "x-service-env: dev" http://service-a/api/endpoint
```

## 🤔 设计权衡（Trade-offs）

### 优势

1. **简洁的设计**：使用标签声明式加入环境，无需复杂的配置管理
2. **符合Istio最佳实践**：基于VirtualService和DestinationRule实现，与Istio原生能力对齐
3. **全链路透明**：环境标识自动传播，应用无需感知路由逻辑
4. **高性能**：Native镜像启动快，资源占用少
5. **可扩展**：易于支持更复杂的路由策略

### 局限性与注意事项

1. **依赖Istio**：必须在安装了Istio的集群中使用
2. **Header传播**：应用需要确保HTTP请求转发时传递`x-service-env`头部（大多数HTTP客户端默认行为）
3. **环境命名全局唯一**：同一命名空间内环境名不能重复
4. **Fallback链深度**：建议fallback链不超过2层，避免路由复杂度过高
5. **标签冲突**：不要在同一Pod上标记多个环境
6. **性能考虑**：大量环境和服务时，Istio配置会增多，可能影响性能

### 对比其他方案

| 方案 | 优势 | 劣势 |
|------|------|------|
| **本方案** | 声明式、自动化、全链路 | 依赖Istio |
| **多集群** | 强隔离 | 成本高、管理复杂 |
| **命名空间隔离** | 简单 | 无法实现fallback、资源浪费 |
| **应用层路由** | 灵活 | 侵入性强、维护成本高 |

## 🛠️ 技术选型说明

### 为什么选择JOSDK而非官方kubernetes-client/java？

经过评估，本项目选择了**Java Operator SDK (JOSDK)**，原因如下：

1. **专注于Operator开发**：JOSDK是专门为编写Kubernetes Operator设计的框架，提供了更高层次的抽象
2. **Spring Boot集成优秀**：`josdk-spring-boot-starter`提供了开箱即用的集成
3. **简化Reconcile逻辑**：自动处理重试、错误处理、状态管理等复杂逻辑
4. **社区活跃**：专注于Operator场景，文档和示例丰富
5. **Spring AOT支持**：与Spring Boot Native集成良好

**kubernetes-client/java**虽然是官方项目，但它是更底层的Kubernetes API客户端库，需要手动实现很多Operator模式的逻辑。对于Operator开发场景，JOSDK提供了更好的开发体验。

## 📚 参考资源

- [Istio官方文档](https://istio.io/latest/docs/)
- [Java Operator SDK](https://javaoperatorsdk.io/)
- [Kubernetes Operator模式](https://kubernetes.io/docs/concepts/extend-kubernetes/operator/)
- [GraalVM Native Image](https://www.graalvm.org/latest/reference-manual/native-image/)

## 🤝 贡献

欢迎提交Issue和Pull Request！

## 📄 许可证

MIT License
