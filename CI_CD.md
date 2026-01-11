# CI/CD 指南 - GitHub Actions & Gitea Actions

本文档介绍如何使用 GitHub Actions（兼容 Gitea Actions）进行自动化构建、测试、打包和发布。

## 📋 工作流概览

### 1. CI - 持续集成 (`.github/workflows/ci.yml`)

**触发条件**:
- Push 到 `main` 或 `develop` 分支
- Pull Request 到 `main` 或 `develop` 分支

**执行内容**:
- ✅ 编译项目
- ✅ 运行测试
- ✅ 代码检查
- ✅ Helm Chart Lint
- ✅ 构建 Docker 镜像（仅 main 分支）

### 2. Release - 发布 (`.github/workflows/release.yml`)

**触发条件**:
- 推送 Git Tag（格式: `v*.*.*`，如 `v0.1.0`）
- 手动触发（workflow_dispatch）

**执行内容**:
- ✅ 构建并测试
- ✅ 使用 Jib 构建并推送 Docker 镜像到 GHCR
- ✅ 打包 Helm Chart
- ✅ 创建 GitHub Release
- ✅ 上传 Helm Chart 到 Release Assets

### 3. Helm Publish - 发布 Helm Chart (`.github/workflows/helm-publish.yml`)

**触发条件**:
- Release 工作流完成后自动触发
- 手动触发

**执行内容**:
- ✅ 推送 Helm Chart 到 OCI Registry (GHCR)

### 4. Docker Build - 手动构建镜像 (`.github/workflows/docker-build.yml`)

**触发条件**:
- 手动触发（用于测试或特殊构建）

**执行内容**:
- ✅ 构建 Docker 镜像
- ✅ 可选推送到 Registry

---

## 🚀 使用指南

### 发布新版本

#### 方式 1: 使用 Git Tag（推荐）

```bash
# 1. 更新版本号
echo "version=0.2.0" > gradle.properties

# 2. 提交更改
git add gradle.properties
git commit -m "chore: bump version to 0.2.0"

# 3. 创建并推送 Tag
git tag v0.2.0
git push origin main
git push origin v0.2.0
```

GitHub Actions 会自动：
1. 构建项目
2. 运行测试
3. 构建并推送 Docker 镜像到 `ghcr.io`
4. 打包 Helm Chart
5. 创建 GitHub Release
6. 上传 Helm Chart 到 Release Assets
7. 推送 Helm Chart 到 OCI Registry

#### 方式 2: 手动触发

1. 访问 GitHub Actions 页面
2. 选择 "Release" 工作流
3. 点击 "Run workflow"
4. 输入版本号（如 `v0.2.0`）
5. 点击 "Run workflow"

---

## 📦 产物说明

### Docker 镜像

**位置**: `ghcr.io/<your-org>/serviceenv-operator:<version>`

**拉取镜像**:
```bash
# 登录 GHCR
echo $GITHUB_TOKEN | docker login ghcr.io -u <username> --password-stdin

# 拉取镜像
docker pull ghcr.io/<your-org>/serviceenv-operator:0.1.0
```

### Helm Chart - Release Assets

**位置**: GitHub Release Assets

**安装方式**:
```bash
# 下载 Helm Chart
wget https://github.com/<your-org>/serviceenv-operator/releases/download/v0.1.0/serviceenv-operator-0.1.0.tgz

# 安装
helm install serviceenv-operator serviceenv-operator-0.1.0.tgz \
  --namespace serviceenv-system \
  --create-namespace
```

### Helm Chart - OCI Registry

**位置**: `oci://ghcr.io/<your-org>/charts/serviceenv-operator`

**安装方式**:
```bash
# 登录 GHCR
echo $GITHUB_TOKEN | helm registry login ghcr.io -u <username> --password-stdin

# 安装
helm install serviceenv-operator \
  oci://ghcr.io/<your-org>/charts/serviceenv-operator \
  --version 0.1.0 \
  --namespace serviceenv-system \
  --create-namespace
```

---

## 🔧 配置说明

### GitHub Secrets

不需要额外配置！工作流使用内置的 `GITHUB_TOKEN`，自动具有以下权限：
- ✅ 读取代码
- ✅ 推送到 GHCR
- ✅ 创建 Release

### GitHub Variables（可选）

如果需要自定义 Registry，可以设置：

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `REGISTRY` | 容器镜像仓库 | `ghcr.io` |

设置方式：
1. 进入仓库 Settings
2. 选择 Secrets and variables → Actions
3. 在 Variables 标签页添加

---

## 🔄 Gitea Actions 兼容性

所有工作流都兼容 Gitea Actions！只需：

### 1. 复制工作流文件

```bash
# GitHub Actions 和 Gitea Actions 使用相同的目录结构
cp -r .github /path/to/gitea/repo/
```

### 2. 配置 Gitea Secrets

在 Gitea 仓库设置中添加：

| Secret 名称 | 说明 | 获取方式 |
|------------|------|---------|
| `GITEA_TOKEN` | Gitea 访问令牌 | 用户设置 → 应用 → 生成新令牌 |

### 3. 修改 Registry（如果使用自托管）

编辑 `.github/workflows/*.yml`，将：
```yaml
env:
  REGISTRY: ghcr.io
```

改为：
```yaml
env:
  REGISTRY: gitea.example.com
```

### 4. Gitea Actions Runner

确保 Gitea 已配置 Actions Runner：

```bash
# 下载 act_runner
wget https://dl.gitea.com/act_runner/latest/act_runner-linux-amd64

# 注册 Runner
./act_runner-linux-amd64 register \
  --instance https://gitea.example.com \
  --token <runner-token>

# 启动 Runner
./act_runner-linux-amd64 daemon
```

---

## 📊 工作流状态徽章

在 `README.md` 中添加状态徽章：

### GitHub Actions

```markdown
![CI](https://github.com/<your-org>/serviceenv-operator/actions/workflows/ci.yml/badge.svg)
![Release](https://github.com/<your-org>/serviceenv-operator/actions/workflows/release.yml/badge.svg)
```

### Gitea Actions

```markdown
![CI](https://gitea.example.com/<your-org>/serviceenv-operator/actions/workflows/ci.yml/badge.svg)
![Release](https://gitea.example.com/<your-org>/serviceenv-operator/actions/workflows/release.yml/badge.svg)
```

---

## 🎯 最佳实践

### 1. 版本管理策略

```bash
# 主版本更新（破坏性变更）
git tag v2.0.0

# 次版本更新（新功能）
git tag v1.1.0

# 补丁版本更新（Bug 修复）
git tag v1.0.1
```

### 2. 分支策略

```
main (生产)
  ↑
develop (开发)
  ↑
feature/* (功能分支)
```

- `main`: 稳定版本，每次合并触发 Docker 构建
- `develop`: 开发版本，每次 Push 运行 CI
- `feature/*`: 功能分支，PR 时运行 CI

### 3. Release Notes

工作流会自动生成 Release Notes，也可以手动编辑：

1. 访问 GitHub Releases
2. 编辑对应的 Release
3. 添加详细的变更说明

### 4. 多环境部署

```yaml
# .github/workflows/deploy-dev.yml
name: Deploy to Dev

on:
  push:
    branches:
      - develop

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to Dev Cluster
        run: |
          helm upgrade --install serviceenv-operator \
            oci://ghcr.io/${{ github.repository_owner }}/charts/serviceenv-operator \
            --version latest \
            --namespace serviceenv-dev \
            --create-namespace \
            --set image.tag=latest
```

---

## 🐛 故障排查

### 问题 1: Jib 构建失败

**错误**: `Unauthorized`

**解决**:
```bash
# 检查 GITHUB_TOKEN 权限
# 确保工作流有 packages: write 权限
```

### 问题 2: Helm Chart 推送失败

**错误**: `403 Forbidden`

**解决**:
```bash
# 1. 确保仓库设置中启用了 Packages
# 2. 检查 GITHUB_TOKEN 权限
# 3. 确保 Chart.yaml 中的版本号正确
```

### 问题 3: Release 创建失败

**错误**: `Resource not accessible by integration`

**解决**:
```yaml
# 在工作流中添加权限
permissions:
  contents: write
  packages: write
```

### 问题 4: Gitea Actions 不触发

**解决**:
```bash
# 1. 检查 Gitea Actions 是否启用
# 2. 检查 Runner 是否在线
# 3. 查看 Gitea 日志
docker logs gitea
```

---

## 📈 监控和通知

### Slack 通知

添加到工作流：

```yaml
- name: Notify Slack
  if: always()
  uses: 8398a7/action-slack@v3
  with:
    status: ${{ job.status }}
    text: 'Release ${{ steps.version.outputs.version }} - ${{ job.status }}'
    webhook_url: ${{ secrets.SLACK_WEBHOOK }}
```

### 企业微信通知

```yaml
- name: Notify WeChat Work
  if: always()
  run: |
    curl -X POST "${{ secrets.WECHAT_WEBHOOK }}" \
      -H 'Content-Type: application/json' \
      -d '{
        "msgtype": "text",
        "text": {
          "content": "Release ${{ steps.version.outputs.version }} - ${{ job.status }}"
        }
      }'
```

---

## 🔐 安全最佳实践

### 1. 最小权限原则

```yaml
permissions:
  contents: read      # 只读代码
  packages: write     # 只写包
```

### 2. 使用 Dependabot

创建 `.github/dependabot.yml`:

```yaml
version: 2
updates:
  - package-ecosystem: "gradle"
    directory: "/"
    schedule:
      interval: "weekly"
  
  - package-ecosystem: "github-actions"
    directory: "/"
    schedule:
      interval: "weekly"
```

### 3. 镜像扫描

添加到工作流：

```yaml
- name: Scan image for vulnerabilities
  uses: aquasecurity/trivy-action@master
  with:
    image-ref: 'ghcr.io/${{ github.repository_owner }}/serviceenv-operator:${{ steps.version.outputs.version }}'
    format: 'sarif'
    output: 'trivy-results.sarif'

- name: Upload Trivy results to GitHub Security
  uses: github/codeql-action/upload-sarif@v2
  with:
    sarif_file: 'trivy-results.sarif'
```

---

## 📚 相关文档

- [GitHub Actions 文档](https://docs.github.com/en/actions)
- [Gitea Actions 文档](https://docs.gitea.com/usage/actions/overview)
- [Jib Gradle Plugin](https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin)
- [Helm OCI Registry](https://helm.sh/docs/topics/registries/)

---

## 🎉 快速命令参考

```bash
# 发布新版本
git tag v0.2.0 && git push origin v0.2.0

# 查看工作流状态
gh workflow list
gh run list --workflow=release.yml

# 下载 Release Assets
gh release download v0.2.0

# 安装 Helm Chart（OCI）
helm install serviceenv-operator \
  oci://ghcr.io/<your-org>/charts/serviceenv-operator \
  --version 0.2.0 \
  --namespace serviceenv-system \
  --create-namespace

# 安装 Helm Chart（Release Assets）
helm install serviceenv-operator \
  https://github.com/<your-org>/serviceenv-operator/releases/download/v0.2.0/serviceenv-operator-0.2.0.tgz \
  --namespace serviceenv-system \
  --create-namespace
```

---

**完全自动化的 CI/CD 流程，专为 Java 开发者设计！** 🚀
