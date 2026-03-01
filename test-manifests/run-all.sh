#!/bin/bash
# 一键测试：部署 -> 等待 reconcile -> 路由验证
# 适用于 v0.9.0+ DependentResource 模式
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECONCILE_WAIT="${RECONCILE_WAIT:-25}"

echo ""
echo "=== 1. 部署测试资源 ==="
"$SCRIPT_DIR/apply.sh"

echo ""
echo "=== 2. 等待 Operator reconcile（${RECONCILE_WAIT}s）==="
echo "    App DependentResource 将创建 VirtualService / DestinationRule"
sleep "$RECONCILE_WAIT"

echo ""
echo "=== 3. 路由验证 ==="
"$SCRIPT_DIR/test.sh"

echo ""
echo "=== 全部完成 ==="
