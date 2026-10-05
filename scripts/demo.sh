#!/usr/bin/env bash
# A local end-to-end demonstration using only the project's synthetic fixture.
set -euo pipefail
cd "$(dirname "$0")/.."
MODE="${1:-}"
if [[ "$MODE" != "" && "$MODE" != "--check" ]]; then
  echo "Usage: scripts/demo.sh [--check]" >&2
  exit 2
fi
PORT="${MRD_DEMO_PORT:-8085}"
BASE="http://127.0.0.1:$PORT"
if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then
  echo "Port $PORT already serves an application; choose another MRD_DEMO_PORT." >&2
  exit 1
fi
mkdir -p .demo
cargo build --release
(cd case-service && ./mvnw -B package -DskipTests > ../.demo/build.log 2>&1)
M=target/release/mrd
"$M" prepare --input tests/fixtures/synth_trans.csv --output .demo/sorted.csv --splits .demo/splits.json
"$M" train --input .demo/sorted.csv --splits .demo/splits.json --out-dir .demo/models \
  --neg-rate 1.0 --trees 80 --depth 3
java -jar case-service/target/case-service.jar --spring.profiles.active=local \
  --server.address=127.0.0.1 --server.port="$PORT" \
  '--spring.datasource.url=jdbc:h2:mem:mrd-demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1' \
  > .demo/service.log 2>&1 &
SERVICE_PID=$!
cleanup() {
  kill "$SERVICE_PID" 2>/dev/null || true
  wait "$SERVICE_PID" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT TERM
READY=false
for ((i=0; i<90; i++)); do
  if ! kill -0 "$SERVICE_PID" 2>/dev/null; then
    tail -30 .demo/service.log >&2
    exit 1
  fi
  if curl -fsS "$BASE/actuator/health" >/dev/null 2>&1; then READY=true; break; fi
  sleep 1
done
if [[ "$READY" != true ]]; then echo "Service did not become healthy" >&2; exit 1; fi
# Public, intentionally fixed credentials belong only to the local demo profile.
MRD_PUSH_PASSWORD=engine-dev "$M" replay --input .demo/sorted.csv --model .demo/models/gbdt.json \
  --push "$BASE/api/alerts" --max-alerts 50 --alerts-out .demo/alerts.jsonl
curl -fsS -u analyst1:analyst1-dev "$BASE/api/cases" > .demo/cases.json
python3 - <<'PY'
import json
from pathlib import Path
cases = json.loads(Path('.demo/cases.json').read_text())
assert cases['totalElements'] > 0, 'No investigation cases created'
print(f"Rust -> Java integration passed: {cases['totalElements']} cases created.")
PY
echo "Investigation desk: $BASE (analyst1 / analyst1-dev)"
if [[ "$MODE" != --check ]]; then
  echo "Press Ctrl+C to stop this isolated demo."
  wait "$SERVICE_PID"
fi
