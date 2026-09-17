# Connect an actual IBM DataPower Gateway

The laptop includes a Quarkus policy simulator so it runs natively on Apple Silicon without requiring an IBM appliance or entitlement. It does **not** package or emulate IBM DataPower. Use an entitled DataPower / API Connect deployment for the real product segment of the talk.

## A practical integration boundary

Expose an HTTPS `/mcp` endpoint in DataPower that authenticates the runtime service identity, applies your organization's rate and payload policies, and forwards to `policy-gateway:8091/mcp` in the deployed environment. Keeping the reference policy service behind DataPower preserves its tool-level checks while DataPower handles enterprise ingress controls. This is the quickest way to connect the runnable demo to the actual gateway without pretending an environment-independent appliance export exists.

Configure the runtime's `MCP_GATEWAY_URL` to the DataPower URL **without** the `/mcp` suffix and set `GATEWAY_KIND='IBM DataPower + tool policy service'`. Both the direct orchestration client and LangChain4j client use this base URL. Forward the Authorization header to the policy service unchanged or map validated read/approval identities onto the corresponding internal credentials using protected gateway configuration. Never expose these internal credentials to browser code.

The policy service must be reachable from the real gateway. Laptop-only Podman DNS names are not accessible to a remote appliance. Deploy the services together in a reachable private environment, or use an explicitly approved secure tunnel for the talk. The launcher never exposes the backend tools or database to the internet.

## Required gateway behavior

- Validate TLS and the caller identity; use separate read and approval identities.
- Preserve JSON-RPC bodies, HTTP response codes, `Accept`, `Content-Type`, `Mcp-Session-Id`, and `Mcp-Protocol-Version`.
- Allow the required JSON-RPC methods: `initialize`, `notifications/initialized`, `ping`, `tools/list`, `tools/call`.
- Forward POST response content as JSON or SSE framing without rewriting tool results. Keep timeouts compatible with the runtime's 15-second tool-call deadline.
- Apply a 32 KB request limit and the chosen shared rate policy. Reject JSON-RPC batches and unknown operations at the policy service.
- Strip externally supplied internal backend credentials; only the tool policy service holds `BACKEND_KEY`.
- Record request IDs and decisions in the enterprise audit system. Use the reference database audit for the demo's application policy decisions.

If your deployment requires a client ID header, extend both `GatewayClient` and the named MCP client's static header configuration consistently. Do not modify only one of the two paths. If your gateway uses OAuth service tokens, integrate approved client-credentials acquisition and renewal; a fixed token in `.env` is not a production token-management strategy.

## Verify the real product path

1. Check `/mcp` connectivity and normal initialization using the runtime.
2. Confirm `tools/list` for the investigator returns only the three read tools.
3. Run the SPA's invalid credential, write escalation and unsafe-argument probes through DataPower.
4. Complete a live investigation and approval, then match request IDs across runtime, DataPower and policy audit.
5. Repeat approval to verify there is still only one follow-up.

No appliance-specific configuration export is supplied: IBM product/version, certificates, identity policies and backend topology must match your actual environment. Start from the official [DataPower container documentation](https://www.ibm.com/docs/en/datapower-gateway/11.0.0?topic=virtual-datapower-gateway-docker) and your API Connect deployment's policy reference. The slide's gateway placement is an architectural concept; this repository uses the product name **IBM DataPower Gateway**.
