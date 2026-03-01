#!/bin/bash
# 删除所有测试资源（删除 namespace 即可清理全部）
set -e
echo "Deleting serviceenv-test namespace and all resources..."
kubectl delete namespace serviceenv-test --ignore-not-found --wait --timeout=120s 2>/dev/null || true
echo "Cleanup complete."
