# IBM DataPower Gateway (local container)

`./demo.sh up` starts a real **IBM DataPower Gateway** container with Podman and puts it
in front of the Quarkus `policy-gateway`. The agents talk to DataPower for real; DataPower
forwards each MCP call to the reference policy service, which keeps its tool-level checks.

```
agent-runtime ─▶ http://127.0.0.1:8788/mcp   DataPower (container)
DataPower     ─▶ http://host.containers.internal:8091/mcp   policy-gateway (laptop)
policy-gateway─▶ http://127.0.0.1:8092/mcp   mcp-tools (laptop)
```

## What the launcher does

- Runs `icr.io/cpopen/datapower/datapower-limited` (the free, non-production developers
  edition; no entitlement or registry login required) as container `runway-datapower`,
  labeled `app=enterprise-ai-runway`, with `DATAPOWER_ACCEPT_LICENSE=true`.
- Publishes the MCP ingress port `8788` on `127.0.0.1` only.
- Mounts [`config/auto-startup.cfg`](config/auto-startup.cfg) read-only into the default
  domain (`/opt/ibm/datapower/drouter/config`). DataPower auto-executes it at boot to
  create a Multi-Protocol Gateway that reverse-proxies `/mcp` to the policy service.
- Binds `policy-gateway` to all interfaces (instead of loopback) only while DataPower is
  enabled, so the container can reach it at `host.containers.internal:8091`. The gateway
  still requires the read/approval bearer keys and still denies any browser-origin call.
- Waits until DataPower accepts traffic, then hands off to the demo. `./demo.sh down`
  (or Ctrl+C) stops the container; the database volume and history are retained.

## Apple Silicon note

The DataPower image is **amd64-only** — there is no native arm64 build. On Apple Silicon
it runs emulated through the Podman machine (libkrun + Rosetta): expect a **~1.5 GB
one-time pull** and a **1–3 minute boot** on each `up`. This is why the demo also ships a
lightweight Quarkus policy simulator: set `GATEWAY_MODE=simulator` before `./demo.sh up`
to skip the container entirely (offline or slow venues). The launcher never exposes the
backend tools or database to the network.

## The gateway config

[`config/auto-startup.cfg`](config/auto-startup.cfg) is intentionally a pass-through
reverse proxy so the demo's security story stays in the policy service:

- An HTTP front-side handler on `8788` and a Multi-Protocol Gateway with a static backend
  of `http://host.containers.internal:8091` and `propagate-uri`, so `/mcp` is forwarded
  verbatim.
- `request-type`/`response-type` are `unprocessed` with an empty processing policy, so
  JSON-RPC and SSE bodies pass through untouched and no `Origin` header is added — the
  policy service's credential, rate and tool-list checks continue to apply.
- Preserves `Authorization`, `Accept`, `Content-Type`, `Mcp-Session-Id` and
  `Mcp-Protocol-Version`; timeouts (120 s) stay above the runtime's 15 s tool deadline.

To watch traffic during the talk, follow the `[datapower]` lines in the `./demo.sh up`
terminal — each forwarded request is logged by the gateway.

## Moving to a production DataPower

The local container is a faithful but minimal stand-in. For a production segment, run an
entitled DataPower / API Connect deployment and keep the same boundary: expose an **HTTPS**
`/mcp` endpoint that authenticates the runtime's service identity, applies your rate and
payload policies, and forwards to the policy service. Then point the runtime's
`MCP_GATEWAY_URL` at that endpoint (base URL, without the `/mcp` suffix) and set
`GATEWAY_KIND` accordingly.

Harden beyond this demo config:

- Terminate TLS and validate the caller identity; use separate read and approval
  identities rather than forwarding a fixed bearer token.
- Keep preserving JSON-RPC bodies, HTTP status codes and the `Accept`, `Content-Type`,
  `Mcp-Session-Id` and `Mcp-Protocol-Version` headers; allow only `initialize`,
  `notifications/initialized`, `ping`, `tools/list` and `tools/call`.
- Enforce the 32 KB request limit and a shared rate policy; reject JSON-RPC batches and
  unknown operations at the policy service.
- Strip externally supplied internal backend credentials; only the policy service holds
  `BACKEND_KEY`. Record request IDs and decisions in the enterprise audit system.
- If your gateway needs a client-ID header, extend both `GatewayClient` and the named MCP
  client's static header configuration together. For OAuth service tokens, integrate
  approved client-credentials acquisition and renewal; a fixed token is not a production
  token-management strategy.

A remote appliance cannot resolve laptop-only Podman DNS names, so in a shared environment
deploy the services together in a reachable private network (see `../kubernetes`) or use
an explicitly approved secure tunnel. IBM product/version, certificates, identity policies
and backend topology must match your actual environment. Start from the official
[DataPower container documentation](https://www.ibm.com/docs/en/datapower-gateway/10.6.x?topic=virtual-datapower-gateway-docker)
and your API Connect deployment's policy reference. The repository uses the product name
**IBM DataPower Gateway**.
