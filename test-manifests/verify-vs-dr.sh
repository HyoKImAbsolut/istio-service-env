#!/bin/bash
# 验证 App DependentResource 是否正确创建 VirtualService / DestinationRule
# 用于调试 v0.9.0+ DependentResource 模式
set -e
NS="${NS:-serviceenv-test}"

echo ""
echo "=== VirtualService（应由 App 的 DependentResource 创建）==="
kubectl get virtualservice -n "$NS" -o wide 2>/dev/null || echo "无 VirtualService 或 Istio CRD 未安装"

echo ""
echo "=== DestinationRule（应由 App 的 DependentResource 创建）==="
kubectl get destinationrule -n "$NS" -o wide 2>/dev/null || echo "无 DestinationRule 或 Istio CRD 未安装"

echo ""
echo "=== App 资源状态 ==="
kubectl get app -n "$NS" -o wide 2>/dev/null || echo "无 App CR"

echo ""
echo "=== 检查 ownerReference（VS/DR 应引用 App）==="
for vs in $(kubectl get virtualservice -n "$NS" -o name 2>/dev/null); do
  echo "--- $vs ---"
  kubectl get "$vs" -n "$NS" -o jsonpath='{.metadata.ownerReferences[*].name}' 2>/dev/null && echo "" || true
done
for dr in $(kubectl get destinationrule -n "$NS" -o name 2>/dev/null); do
  echo "--- $dr ---"
  kubectl get "$dr" -n "$NS" -o jsonpath='{.metadata.ownerReferences[*].name}' 2>/dev/null && echo "" || true
done
