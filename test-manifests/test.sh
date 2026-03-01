#!/bin/bash
# 执行路由验证测试（使用 httpbin Pod 内 Python 发起请求，无需额外镜像）
set -e
NS=serviceenv-test
CLIENT="deploy/httpbin-prod"

run_req() {
  local url="$1"
  local header="$2"
  local py
  if [ -n "$header" ]; then
    py="import urllib.request; r=urllib.request.urlopen(urllib.request.Request('$url',headers={'x-service-env':'$header'})); print(r.getcode())"
  else
    py="import urllib.request; r=urllib.request.urlopen('$url'); print(r.getcode())"
  fi
  kubectl exec -n $NS $CLIENT -c httpbin -- python3 -c "$py" 2>/dev/null || echo "ERR"
}

echo ""
echo "=== 1. 环境隔离：httpbin 按 x-service-env 路由到不同 subset ==="
echo -n "prod: HTTP "
run_req "http://httpbin.$NS.svc.cluster.local:8000/get" "prod"

echo -n "base: HTTP "
run_req "http://httpbin.$NS.svc.cluster.local:8000/get" "base"

echo -n "dev: HTTP "
run_req "http://httpbin.$NS.svc.cluster.local:8000/get" "dev"

echo ""
echo "=== 2. Fallback：dev 无 fallback-target 部署，应 fallback 到 base 返回 200 ==="
echo -n "fallback-target x-service-env: dev: HTTP "
run_req "http://fallback-target.$NS.svc.cluster.local:8000/get" "dev"

echo -n "fallback-target x-service-env: prod: HTTP "
run_req "http://fallback-target.$NS.svc.cluster.local:8000/get" "prod"

echo ""
echo "=== 3. 无 x-service-env header（默认路由）==="
echo -n "HTTP "
run_req "http://httpbin.$NS.svc.cluster.local:8000/get" ""

echo ""
echo "Tests completed. 预期: 1/2/3 均返回 200."
