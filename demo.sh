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
GATEWAY_KIND='Local policy simulator'
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
  help|-h|--help) printf 'Usage: ./demo.sh {init|up|credentials|status|down|smoke}\nup streams Quarkus Dev Mode logs; Ctrl+C stops the demo and preserves history.\n' ;;
  *) echo "Unknown command: $command. Run ./demo.sh help." >&2; exit 2 ;;
esac
