#!/bin/bash
# 路由验证测试：从集群内 curl-client 发起请求（Istio 才能按 x-service-env 路由），通过响应体判断是否路由到正确分组 Pod
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NS=serviceenv-test
FAILED=0
# 集群内服务地址（同 namespace 可用短名）
HTTPBIN_URL="http://httpbin:8000"
FALLBACK_URL="http://fallback-target:8000"

# 从集群内 curl-client pod 发起请求（走 Istio 路由，busybox 用 wget）
run_curl() {
  local url="$1"
  local header="$2"
  if [ -n "$header" ]; then
    kubectl exec -n $NS deploy/curl-client -c curl -- wget -qO- --header="x-service-env: $header" "$url" 2>/dev/null
  else
    kubectl exec -n $NS deploy/curl-client -c curl -- wget -qO- "$url" 2>/dev/null
  fi
}

# 验证响应体是否包含预期标识（hashicorp/http-echo 返回 -text 参数值）
assert_routed_to() {
  local result="$1"
  local expected_text="$2"
  local test_name="$3"
  local clean_result
  clean_result=$(echo "$result" | tr -d '\n\r' | head -c 100)
  if echo "$result" | grep -q "$expected_text"; then
    echo "  ✓ $test_name: 路由正确 响应含 $expected_text"
  elif echo "$result" | grep -q "Not Found\|404\|error"; then
    echo "  ✗ $test_name: 请求失败 ($clean_result)"
    FAILED=1
  else
    echo "  ✗ $test_name: 响应 $clean_result 不含预期 $expected_text"
    FAILED=1
  fi
}

echo ""
echo "=== 确保 curl-client 已部署 ==="
kubectl apply -f "$SCRIPT_DIR/04-curl-client.yaml" 2>/dev/null
kubectl rollout status -n $NS deploy/curl-client --timeout=30s 2>/dev/null || true

echo ""
echo "=== 从集群内 curl-client 发起请求（走 Istio 路由，按 x-service-env 选择 Pod 分组）==="

echo ""
echo "=== 1. 环境隔离：httpbin 按 x-service-env 路由到对应分组 Pod ==="
assert_routed_to "$(run_curl ${HTTPBIN_URL}/ prod)" "httpbin-prod" "x-service-env: prod"
assert_routed_to "$(run_curl ${HTTPBIN_URL}/ base)" "httpbin-base" "x-service-env: base"
assert_routed_to "$(run_curl ${HTTPBIN_URL}/ dev)" "httpbin-dev" "x-service-env: dev"

echo ""
echo "=== 2. Fallback：dev 无 fallback-target 部署，应 fallback 到 base 分组 ==="
assert_routed_to "$(run_curl ${FALLBACK_URL}/ dev)" "fallback-target-base" "fallback-target x-service-env: dev (fallback 到 base)"
assert_routed_to "$(run_curl ${FALLBACK_URL}/ prod)" "fallback-target-prod" "fallback-target x-service-env: prod"

echo ""
echo "=== 3. 无 x-service-env header（默认路由）==="
DEFAULT=$(run_curl ${HTTPBIN_URL}/)
if [ -n "$DEFAULT" ]; then
  echo "  → 默认路由响应: $(echo "$DEFAULT" | tr -d '\n' | head -c 80)"
else
  echo "  → 默认路由无响应"
  FAILED=1
fi

echo ""
if [ $FAILED -eq 0 ]; then
  echo "全部通过 ✓"
else
  echo "存在失败 ✗"
  exit 1
fi
