#!/bin/bash
# 部署测试资源到集群
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
echo "Applying test manifests from $SCRIPT_DIR"
kubectl apply -f "$SCRIPT_DIR/00-namespace.yaml"
kubectl apply -f "$SCRIPT_DIR/01-serviceenv.yaml"
kubectl apply -f "$SCRIPT_DIR/02-services.yaml"
kubectl apply -f "$SCRIPT_DIR/05-apps.yaml"
kubectl apply -f "$SCRIPT_DIR/03-deployments.yaml"
kubectl apply -f "$SCRIPT_DIR/04-curl-client.yaml"
echo "Waiting for pods to be ready..."
kubectl wait --for=condition=Ready pod -l app=httpbin -n serviceenv-test --timeout=120s 2>/dev/null || true
echo "Done. 等待 Operator reconcile（约 15s）后执行: ./test.sh"
