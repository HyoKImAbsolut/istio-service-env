# GraalVM Native Image 运行时镜像
# 注意：Native Image 在 GitHub Actions 中构建，这里只打包运行时
FROM ubuntu:22.04

# 安装运行时依赖
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 添加非 root 用户
RUN groupadd -r appuser && useradd -r -g appuser -u 1000 appuser

WORKDIR /app

# 从本地构建目录复制 Native Image 可执行文件
COPY build/native/nativeCompile/serviceenv-operator /app/serviceenv-operator

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
