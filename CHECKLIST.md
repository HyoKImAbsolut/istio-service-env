# 项目检查清单 ✅

## 📋 核心文件检查

### 1. Gradle 构建 ✅
- [x] `build.gradle.kts` - 简洁，只有构建逻辑，无部署逻辑
- [x] `gradle.properties` - 只有版本和基础配置
- [x] `settings.gradle.kts` - 项目名称配置

### 2. Dockerfile ✅
- [x] 使用 GraalVM Native Image 构建
- [x] 多阶段构建优化镜像大小
- [x] 非 root 用户运行
- [x] 安全配置（readOnlyRootFilesystem）

### 3. Helm Chart ✅
- [x] `Chart.yaml` - 元数据正确
- [x] `values.yaml` - 简洁（18 行），只有核心配置
- [x] `templates/crd.yaml` - CRD 完整定义 ✅ **已修复**
- [x] `templates/deployment.yaml` - 简洁的 Deployment
- [x] `templates/serviceaccount.yaml` - ServiceAccount
- [x] `templates/rbac.yaml` - ClusterRole 和 ClusterRoleBinding
- [x] 删除了不必要的文件（service.yaml, _helpers.tpl, NOTES.txt）

### 4. GitHub Actions ✅
- [x] 单一工作流 `ci-cd.yml`
- [x] 只在 Tag 时触发发布
- [x] 使用官方 Actions（setup-helm, docker/build-push-action）
- [x] 正确的发布顺序：Build → Docker → Helm Package → Create Release → Push OCI
- [x] 步骤命名清晰 ✅ **已修复**

## 🔍 配置一致性检查

### 镜像仓库
- [x] Workflow: `ghcr.io/${{ github.repository_owner }}/serviceenv-operator`
- [x] values.yaml: `ghcr.io/zaeyi/serviceenv-operator`
- ⚠️ **需要确保 `github.repository_owner` 和 `zaeyi` 一致**

### 版本管理
- [x] Chart.yaml: `version: 0.1.0`, `appVersion: 0.1.0`
- [x] gradle.properties: `version=0.1.0`
- [x] Workflow 自动更新版本号

### 命名一致性
- [x] Chart.Name: `serviceenv-operator`
- [x] Deployment: 使用 `{{ .Chart.Name }}`
- [x] ServiceAccount: 使用 `{{ .Chart.Name }}`
- [x] RBAC: 使用 `{{ .Chart.Name }}`

## 🚀 发布流程检查

### Tag 发布流程
```bash
git tag v0.1.0
git push origin v0.1.0
```

**自动执行**:
1. ✅ Checkout 代码
2. ✅ Setup GraalVM JDK 25
3. ✅ 获取版本号
4. ✅ 构建项目（`./gradlew build -x test`）
5. ✅ 运行测试（`./gradlew test`）
6. ✅ 登录 GHCR
7. ✅ Setup Docker Buildx
8. ✅ 构建并推送 Docker 镜像（amd64 + arm64）
9. ✅ 更新 Helm Chart 版本
10. ✅ Setup Helm
11. ✅ 打包 Helm Chart
12. ✅ 创建 GitHub Release（附带 Helm Chart）
13. ✅ 推送 Helm Chart 到 OCI Registry

## 📦 产物检查

### Docker 镜像
- 位置: `ghcr.io/<your-org>/serviceenv-operator:0.1.0`
- 标签: `0.1.0`, `latest`
- 架构: `linux/amd64`, `linux/arm64`

### Helm Chart
- Release Assets: `serviceenv-operator-0.1.0.tgz`
- OCI Registry: `oci://ghcr.io/<your-org>/charts/serviceenv-operator`

## 🔧 本地测试

### 构建测试
```bash
# 构建 JAR
./gradlew build

# 运行测试
./gradlew test

# 构建 Native Image（可选）
./gradlew nativeCompile

# 构建 Docker 镜像
docker build -t serviceenv-operator:test .

# 测试 Helm Chart
helm lint helm/serviceenv-operator
helm template test helm/serviceenv-operator
```

### 安装测试
```bash
# 从 OCI Registry 安装
helm install serviceenv-operator \
  oci://ghcr.io/<your-org>/charts/serviceenv-operator \
  --version 0.1.0 \
  --namespace serviceenv-system \
  --create-namespace

# 检查状态
kubectl get pods -n serviceenv-system
kubectl get serviceenvs -A
```

## ⚠️ 注意事项

1. **首次发布前**:
   - 确保 GitHub 仓库设置中启用了 Packages
   - 确保 `GITHUB_TOKEN` 有 `packages:write` 权限

2. **镜像仓库**:
   - 确认 `values.yaml` 中的 `image.repository` 与实际仓库一致
   - 如果使用私有仓库，需要配置 `imagePullSecrets`

3. **CRD 安装**:
   - CRD 会随 Helm Chart 一起安装
   - 卸载时 CRD 不会自动删除（Helm 标准行为）

4. **权限**:
   - Operator 需要 ClusterRole 权限
   - 确保 Kubernetes 集群允许创建 ClusterRole

## ✅ 最终检查

- [x] 所有文件简洁清晰
- [x] 职责分离明确（Gradle/Docker/Helm/CI）
- [x] 没有冗余配置
- [x] 使用官方标准 Actions
- [x] 发布流程正确
- [x] CRD 定义完整
- [x] 安全配置到位

## 🎉 准备就绪！

项目已经过完整检查，可以进行首次发布！

```bash
git tag v0.1.0
git push origin v0.1.0
```
