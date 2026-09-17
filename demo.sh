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
check_podman() {
  command -v podman >/dev/null || { echo 'Install Podman, then run podman machine init and podman machine start.'; exit 1; }
  podman info >/dev/null || { echo 'Start your VM with: podman machine start'; exit 1; }
}
wait_http() {
  local name=$1 port=$2
  for i in {1..90}; do
    if [[ $(podman inspect --format '{{.State.Running}}' "$name") != true ]]; then
      podman logs --tail 40 "$name"; echo "$name exited during startup"; exit 1
    fi
    if podman exec "$name" bash -c "exec 3<>/dev/tcp/127.0.0.1/$port; printf 'GET /q/health/ready HTTP/1.0\r\n\r\n' >&3; head -1 <&3" 2>/dev/null | grep -q '200'; then return; fi
    sleep 1
  done
  podman logs --tail 40 "$name"
  echo "$name did not become ready"; exit 1
}
up() {
  load; check_podman
  if [[ ${2:-} != --skip-build ]]; then
    if [[ -z ${JAVA_HOME:-} && -x "$HOME/.sdkman/candidates/java/current/bin/java" ]]; then
      export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
    fi
    ./mvnw -B verify
  fi
  podman network exists runway-net || podman network create runway-net >/dev/null
  podman volume exists runway-data || podman volume create runway-data >/dev/null
  if ! podman container exists runway-db; then
    podman run -d --name runway-db --network runway-net --network-alias database \
      --label app=enterprise-ai-runway -v runway-data:/var/lib/postgresql/data \
      -e POSTGRES_USER=runway -e POSTGRES_PASSWORD="$DB_PASSWORD" -e POSTGRES_DB=runway \
      --memory=512m docker.io/library/postgres:17.11 >/dev/null
  else podman start runway-db >/dev/null; fi
  for i in {1..60}; do
    if podman exec runway-db pg_isready -U runway -d runway >/dev/null 2>&1; then break; fi
    sleep 1
  done
  podman exec runway-db pg_isready -U runway -d runway >/dev/null
  for module in mcp-tools policy-gateway agent-runtime; do
    podman build -q -f Containerfile --build-arg MODULE="$module" -t "localhost/runway-$module:local" .
  done
  # Recreate only this project's application containers; the database volume is retained.
  for name in runway-agent runway-gateway runway-tools; do
    if podman container exists "$name"; then podman stop -t 10 "$name" >/dev/null; podman rm "$name" >/dev/null; fi
  done
  export DB_URL=jdbc:postgresql://database:5432/runway DB_USER=runway
  podman run -d --name runway-tools --network runway-net --network-alias mcp-tools --label app=enterprise-ai-runway \
    --read-only --tmpfs /tmp:rw,size=64m --cap-drop=all --security-opt=no-new-privileges --memory=512m \
    -e DB_URL -e DB_USER -e DB_PASSWORD -e BACKEND_KEY localhost/runway-mcp-tools:local >/dev/null
  wait_http runway-tools 8092
  podman run -d --name runway-gateway --network runway-net --network-alias policy-gateway --label app=enterprise-ai-runway \
    --read-only --tmpfs /tmp:rw,size=64m --cap-drop=all --security-opt=no-new-privileges --memory=512m \
    -e DB_URL -e DB_USER -e DB_PASSWORD -e BACKEND_KEY -e GATEWAY_READ_KEY -e GATEWAY_WRITE_KEY \
    -e MCP_BACKEND_URL=http://mcp-tools:8092 localhost/runway-policy-gateway:local >/dev/null
  wait_http runway-gateway 8091
  podman run -d --name runway-agent --network runway-net --label app=enterprise-ai-runway \
    --read-only --tmpfs /tmp:rw,size=64m --cap-drop=all --security-opt=no-new-privileges --memory=768m \
    -p 127.0.0.1:8090:8090 -e DB_URL -e DB_USER -e DB_PASSWORD -e DEMO_API_KEY -e GATEWAY_READ_KEY -e GATEWAY_WRITE_KEY \
    -e OPENAI_API_KEY -e LLM_BASE_URL -e LLM_MODEL -e LLM_API_KEY -e GATEWAY_KIND \
    -e LOCAL_AUTO_CONNECT="${LOCAL_AUTO_CONNECT:-true}" \
    -e MCP_GATEWAY_URL="${MCP_GATEWAY_URL:-http://policy-gateway:8091}" localhost/runway-agent-runtime:local >/dev/null
  wait_http runway-agent 8090
  printf '\nDemo ready: http://localhost:8090\nLocal browser connection: %s\nManual/API access key: ./demo.sh credentials\n\n' "${LOCAL_AUTO_CONNECT:-true}"
}
case "$command" in
  init) init ;;
  up) up "$@" ;;
  credentials) load; printf 'Presenter key: %s\n' "$DEMO_API_KEY" ;;
  status) check_podman; podman ps -a --filter label=app=enterprise-ai-runway ;;
  logs) podman logs -f --tail 60 "${2:-runway-agent}" ;;
  down) check_podman; for n in runway-agent runway-gateway runway-tools runway-db; do if podman container exists "$n"; then podman stop -t 10 "$n" >/dev/null; fi; done; echo 'Stopped. Database and history retained.' ;;
  smoke) load; python3 scripts/smoke.py ;;
  *) printf 'Usage: ./demo.sh {init|up [--skip-build]|credentials|status|logs [container]|down|smoke}\n' ;;
esac
