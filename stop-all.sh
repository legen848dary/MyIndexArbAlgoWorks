#!/usr/bin/env bash
set -euo pipefail

echo "=== Index Arb Algo — Stopping All Services ==="

# Check Docker is running
if ! docker info > /dev/null 2>&1; then
  echo "ERROR: Docker is not running."
  exit 1
fi

echo "→ Stopping and removing containers..."
docker compose down

echo ""
echo "✅ All services stopped."
echo "   (Shared memory at /dev/shm/aeron is cleaned up automatically by the containers.)"
echo ""
echo "   To also remove built images, run:  docker compose down --rmi local"
echo "   To remove all data volumes, run:   docker compose down -v"
