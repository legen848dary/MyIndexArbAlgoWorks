#!/usr/bin/env bash
set -euo pipefail

echo "=== Index Arb Algo — Docker Compose Launcher ==="

# Check Docker is running
if ! docker info > /dev/null 2>&1; then
  echo "ERROR: Docker is not running. Please start Docker Desktop first."
  exit 1
fi

echo "→ Building and starting all services..."
docker compose up -d

echo ""
echo "✅ Services started FAST MODE:"
echo "   Dashboard:   http://localhost:3000"
echo "   Web Gateway: ws://localhost:8080/ws"
echo ""
echo "→ Tailing logs (Ctrl+C to detach, services keep running)..."
docker compose logs -f
