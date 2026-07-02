#!/usr/bin/env bash
set -euo pipefail
CLUSTER_NAME="${KIND_CLUSTER_NAME:-payment-gateway-demo}"
kind delete cluster --name "$CLUSTER_NAME"
