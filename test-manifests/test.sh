#!/bin/bash
# 路由验证测试：从集群内 curl-client 发起请求（Istio 才能按 x-service-env 路由）
# 使用 traefik/whoami 返回 Pod hostname，精确验证请求是否打到对应 Pod
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NS=serviceenv-test
FAILED=0
HTTPBIN_URL="http://httpbin:8000"

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

# 从 whoami 响应中解析 Hostname（K8s 中即 Pod 名，格式如 "Hostname :  httpbin-prod-xxx"）
get_hostname_from_response() {
  echo "$1" | grep -iE '^Hostname' | head -1 | sed 's/^Hostname[[:space:]]*:[[:space:]]*//' | tr -d '\r\n' | awk '{print $1}'
}

# 验证响应来自预期 Pod（通过 hostname 前缀匹配，如 httpbin-prod-xxx）
assert_routed_to_pod() {
  local result="$1"
  local expected_prefix="$2"
  local test_name="$3"
  local hostname
  hostname=$(get_hostname_from_response "$result")
  if [ -z "$hostname" ]; then
    echo "  ✗ $test_name: 无法解析 Hostname 响应 $([ -n "$result" ] && echo "$result" | head -c 80)"
    FAILED=1
  elif [[ "$hostname" == $expected_prefix* ]]; then
    echo "  ✓ $test_name: 路由正确 响应来自 Pod $hostname"
  else
    echo "  ✗ $test_name: 响应来自 $hostname 预期前缀 $expected_prefix"
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
assert_routed_to_pod "$(run_curl ${HTTPBIN_URL}/ prod)" "httpbin-prod" "x-service-env: prod"
assert_routed_to_pod "$(run_curl ${HTTPBIN_URL}/ base)" "httpbin-base" "x-service-env: base"
assert_routed_to_pod "$(run_curl ${HTTPBIN_URL}/ dev)" "httpbin-dev" "x-service-env: dev"

echo ""
echo "=== 2. 无 x-service-env header（默认路由）==="
DEFAULT=$(run_curl ${HTTPBIN_URL}/)
DEFAULT_HOSTNAME=$(get_hostname_from_response "$DEFAULT")
if [ -n "$DEFAULT_HOSTNAME" ]; then
  echo "  → 默认路由响应来自 Pod: $DEFAULT_HOSTNAME"
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
