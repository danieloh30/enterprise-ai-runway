#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
command=${1:-help}
init() {
  if [[ ! -f .env ]]; then
    umask 077
    for key in DEMO_API_KEY DB_PASSWORD GATEWAY_READ_KEY GATEWAY_WRITE_KEY BACKEND_KEY; do
      printf '%s=%s\n' "$key" "$(openssl rand -hex 24)" >> .env
    done
    cat >> .env <<'ENV'
# Export OPENAI_API_KEY before ./demo.sh up for live AI.
LLM_BASE_URL=https://api.openai.com/v1
LLM_MODEL=gpt-4.1-mini
# ./demo.sh up starts a real IBM DataPower Gateway container in front of the policy
# service (running it accepts the IBM DataPower license). The image is amd64-only, so
# it runs emulated on Apple Silicon: a ~1.5 GB one-time pull and a 1-3 minute boot.
DATAPOWER_IMAGE=icr.io/cpopen/datapower/datapower-limited:10.6.0.0
DATAPOWER_PORT=8788
# DataPower WebGUI: https://127.0.0.1:9090 (login admin/admin) to browse the gateway live.
DATAPOWER_MGMT_PORT=9090
# Skip DataPower and use the bundled Quarkus policy simulator (offline/slow venues):
#   GATEWAY_MODE=simulator ./demo.sh up
# Leave GATEWAY_MODE and GATEWAY_KIND out of this file so that override still works.
ENV
    printf 'Created .env with unique local credentials.\n'
  fi
}
load() { init; set -a; source .env; set +a; }
if [[ $# -gt 1 ]]; then
  echo 'Use ./demo.sh up without options; Quarkus Dev Mode compiles changes automatically.' >&2
  exit 2
fi
case "$command" in
  init) init ;;
  up)
    load
    if [[ -z ${JAVA_HOME:-} && -x "$HOME/.sdkman/candidates/java/current/bin/java" ]]; then
      export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
    fi
    exec python3 scripts/dev.py up ;;
  credentials) load; printf 'Presenter key: %s\n' "$DEMO_API_KEY" ;;
  status|down) exec python3 scripts/dev.py "$command" ;;
  smoke) load; python3 scripts/smoke.py ;;
  reset)
    if ! podman container exists runway-db 2>/dev/null; then
      echo 'runway-db is not running. Start the demo with ./demo.sh up first.' >&2; exit 1
    fi
    podman exec runway-db psql -U runway -d runway -q \
      -c 'TRUNCATE runs, followups, gateway_audit RESTART IDENTITY CASCADE;'
    printf 'Cleared execution history, follow-ups and the gateway decision log. Seeded incidents are kept.\n' ;;
  help|-h|--help) printf 'Usage: ./demo.sh {init|up|credentials|status|down|smoke|reset}\nup streams Quarkus Dev Mode logs; Ctrl+C stops the demo and preserves history.\nup also starts a real IBM DataPower Gateway container (amd64, emulated on Apple\nSilicon: ~1.5 GB one-time pull, 1-3 min boot). GATEWAY_MODE=simulator skips it.\nThe DataPower WebGUI is at https://127.0.0.1:9090 (login admin/admin).\nreset clears execution history and the gateway decision log for a clean demo run.\n' ;;
  *) echo "Unknown command: $command. Run ./demo.sh help." >&2; exit 2 ;;
esac
