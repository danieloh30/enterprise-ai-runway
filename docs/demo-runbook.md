# Presenter runbook

Configure `OPENAI_API_KEY` and run `./demo.sh up` before the session. Leave that terminal open for Quarkus Dev Mode logs and live reload. The default provider is OpenAI; a model download is needed only for optional Ollama. Optionally run `./demo.sh smoke` and `SMOKE_MODE=live ./demo.sh smoke` in a second terminal to verify the complete flow, then open the SPA; the local browser connects automatically. Leave the app running; the timer is presentation guidance, not a shutdown clock.

## Opening — 1 minute

“This is a governed incident investigation. The AI can inspect evidence and recommend a follow-up. It cannot grant itself permission to act.” Point out that the first Bob arrow represents development; the agent-to-gateway arrow represents runtime traffic.

## Build — 3 minutes

Build the default blueprint. Inspect the MCP configuration and agent interface. The app identifies this as a template operation. Copy the prompt into the real IBM Bob IDE session. Use [the prepared prompt](ibm-bob-prompt.md) to add one tested, small enhancement; inspect the diff before accepting. Keep a prepared branch if venue connectivity is uncertain. Do not claim the UI is invoking Bob.

If live coding takes more than two minutes, show `InvestigatorAgent.java` and the blueprint and move on. The runnable system already contains the complete implementation.

## Secure — 3 minutes

1. Invalid credential → HTTP 401.
2. Read-only agent asks for `create_followup` → HTTP 403.
3. SQL-shaped incident ID → HTTP 400.

Each button issues an actual request to the gateway. Show the persisted decision log and request ID. Explain that checking prompts alone is insufficient: capability boundaries must be enforced outside the model. The local component is a simulator; use the included DataPower integration path when an entitled endpoint is available.

## Execute — 4 minutes

Select `INC-2042` and Live AI. The orchestrator first gathers three observations via MCP. The investigator then chooses its own read tools. The reviewer receives independently gathered observations and the investigator's conclusion. Open the security tab while waiting if you want to show tool calls in the gateway log.

Explain the evidence: seeded checkout p95 is 1840 ms versus a 220 ms baseline, the connection pool is 98% utilized, and a recorded change reduced the pool from 40 to 8. These observations suggest a cause; they do not prove causality. The reviewer should recommend verification in staging.

## Approve — 2 minutes

Read the report. Click Approve follow-up. Point out the task ID and the absence of a production mutation. Reopen the run from history. Repeat approval via the API or show the smoke test to explain idempotency: the unique run ID prevents duplicates. For a second incident, decline and show that later approval is rejected.

## Close — 2 minutes

Show the Java interfaces, `GatewayPolicy` and the `create_followup` SQL guard. Explain how DataPower/OIDC/TLS and external secret management fit the same boundaries. Ask the audience which enterprise tool they would expose first.

## Recovery cues

- Model unavailable: explicitly choose Rehearsal. “This mode exercises the same network and persistence path; it does not call an LLM.”
- Live timeout: failed runs remain visible. Start a new rehearsal run; never silently substitute a canned AI answer.
- Stale browser/session: reload the page or click the gear to reconnect automatically. If manual mode is enabled, retrieve `./demo.sh credentials` and enter the key.
- Too many requests: wait until the next minute. Do not raise limits during the security explanation.
- Gateway unavailable: check the `[policy-gateway]` output in the `./demo.sh up` terminal; do not bypass it with direct tool calls.
- Avoid a surprise reset: Ctrl+C in the launcher or `./demo.sh down` stops the demo and keeps the database volume and all history. Finish active investigations before triggering live reload.
