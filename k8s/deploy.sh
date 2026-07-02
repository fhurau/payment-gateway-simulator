#!/usr/bin/env bash
# kind-based K8s deploy demo per DESIGN.md §17 - a talking-point/parity layer, never a runtime
# requirement (docker compose remains the primary way to run this project, per §13). Zero-cost:
# kind runs entirely on the local Docker daemon, no cloud account, same approach as
# distributed-auth-platform's demo.
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER_NAME="${KIND_CLUSTER_NAME:-payment-gateway-demo}"
IMAGES=(
  payment-gateway-simulator-api-gateway:latest
  payment-gateway-simulator-payment-processor:latest
  payment-gateway-simulator-notification-service:latest
)

if ! kind get clusters | grep -qx "$CLUSTER_NAME"; then
  echo "==> Creating kind cluster $CLUSTER_NAME"
  kind create cluster --name "$CLUSTER_NAME"
fi

echo "==> Building service images"
docker compose build api-gateway payment-processor notification-service

echo "==> Loading images into kind"
for image in "${IMAGES[@]}"; do
  kind load docker-image "$image" --name "$CLUSTER_NAME"
done

echo "==> Creating postgres-init ConfigMap from the existing seed scripts"
kubectl create configmap postgres-init \
  --from-file=postgres-init/init-databases.sql \
  --dry-run=client -o yaml | kubectl apply -f -

echo "==> Applying manifests"
kubectl apply -f k8s/postgres.yaml -f k8s/redis.yaml -f k8s/kafka.yaml \
  -f k8s/api-gateway.yaml -f k8s/payment-processor.yaml -f k8s/notification-service.yaml

echo "==> Waiting for all deployments to become available (this can take a few minutes)"
kubectl wait --for=condition=available --timeout=300s \
  deployment/postgres deployment/redis deployment/kafka \
  deployment/api-gateway deployment/payment-processor deployment/notification-service

echo "==> Deployed. Try it:"
echo "    kubectl port-forward svc/api-gateway 8080:8080"
echo "    curl -X POST http://localhost:8080/auth/token"
