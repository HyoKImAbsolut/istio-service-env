# GraalVM Native Image 多阶段构建
# 阶段1: 构建 Native Image
FROM ghcr.io/graalvm/native-image-community:25 AS builder

WORKDIR /workspace

# 复制 Gradle wrapper 和配置文件
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# 下载依赖（利用 Docker 缓存）
RUN ./gradlew dependencies --no-daemon

# 复制源代码
COPY src src

# 构建 Native Image
# 使用 Spring Boot 的 nativeCompile 任务
RUN ./gradlew nativeCompile --no-daemon

# 阶段2: 运行时镜像（使用最小基础镜像）
FROM ubuntu:22.04

# 安装运行时依赖
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 添加非 root 用户
RUN groupadd -r appuser && useradd -r -g appuser -u 1000 appuser

WORKDIR /app

# 从构建阶段复制 Native Image 可执行文件
COPY --from=builder /workspace/build/native/nativeCompile/serviceenv-operator /app/serviceenv-operator

# 设置可执行权限
RUN chmod +x /app/serviceenv-operator && \
    chown appuser:appuser /app/serviceenv-operator

# 切换到非 root 用户
USER appuser:appuser

# 设置环境变量
ENV SPRING_PROFILES_ACTIVE=production

EXPOSE 8080

# 直接运行 Native Image 可执行文件
ENTRYPOINT ["/app/serviceenv-operator"]

# 元数据标签
LABEL maintainer="zaeyi" \
      org.opencontainers.image.title="ServiceEnv Operator" \
      org.opencontainers.image.description="Kubernetes Operator for managing service environments with Istio (GraalVM Native)" \
      org.opencontainers.image.source="https://github.com/zaeyi/serviceenv-operator"
