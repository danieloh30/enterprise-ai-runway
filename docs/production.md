# Deployment beyond the local demo

The local stack is deliberately a single presenter workspace with synthetic business data. The included containers are runnable deployment artifacts; organization-specific controls below must be supplied and validated before production use.

## Identity and human decisions

The runtime includes Quarkus OIDC bearer authentication. Set:

```properties
OIDC_ENABLED=true
OIDC_AUTH_SERVER_URL=https://identity.example.com/realms/enterprise
OIDC_CLIENT_ID=runway
```

OIDC tokens must include the audience `runway` (or the configured client ID), and a `groups` claim containing `presenter`. Approve/reject additionally requires `approver`. In OIDC mode the local presenter key is not accepted. Use an access token in the SPA's connection dialog; tokens remain in memory. This small demo does not implement an OAuth login/refresh UI. Validate the integration against your issuer; it was not tested against a real organization identity provider.

For a multi-user product, add run ownership/tenant authorization, identity-linked approval records, authenticated session handling and token renewal. The current role model intentionally shares one workspace; it is unsuitable for mutually untrusted tenants.

## Data and recovery

PostgreSQL persists runs, evidence, decisions and unique follow-up records. SQL uses bound values, bounded recent-history queries and a 10-second statement timeout. Separate runtime, gateway, tools and migration database identities. The local launcher uses one development owner account for convenience; do not replicate that permission model in production.

Typical grants: runtime reads incidents/followups and manages runs; gateway inserts into gateway_audit; tools read incidents/runs and insert/select followups. Run Flyway migrations with a separate migration identity before rolling out application pods. Disable automatic migrations for those pods with `QUARKUS_FLYWAY_MIGRATE_AT_START=false`.

Use TLS database connections, encrypted storage, tested backups, retention/archival jobs for runs and gateway_audit, and appropriate controls for model-bound data. The demo has no automatic deletion or retention job. Evidence fields are seeded and contain no customer PII.

The in-process execution limit is per replica. Use a durable worker queue and shared admission control for larger deployments. Current interrupted jobs are marked failed rather than resumed; successful writes are reconciled by their unique run ID. Do not describe this as a durable workflow engine. Request cancellation cannot guarantee a remote provider stopped computing; only the server's state machine determines whether results remain eligible for approval.

## Network and gateway

Terminate browser TLS at an approved ingress. Protect management endpoints (`/q/*`) using private management networking or ingress restrictions. Use TLS/mTLS between services or your organization's service mesh. The default OpenAI connection uses HTTPS; inject `OPENAI_API_KEY` from your secret manager into the runtime only. Restrict model egress and approve the data sent to the provider. Optional native Ollama over local HTTP is for the laptop demo only.

The policy simulator supports request/response MCP methods needed by this demo, not every MCP capability. Its fixed-window limit is per principal **per gateway process**, so deploy one gateway replica or replace the limiter with DataPower/API Connect or another shared policy service. Gateway audit admission is fail-closed when the audit store is unavailable. The audit is useful operational evidence, not cryptographically tamper-proof storage; export it to your central audit system.

Model prompts are defense in depth. Enforced tool allowlists and the database approval predicate are the authorization boundary. Add systematic prompt-injection, data leakage, model-quality and load evaluations for your actual models and datasets. No autonomous production change tool is present here.

## Kubernetes

See [deployment templates](../deploy/kubernetes/README.md). Supply built image references, a pre-migrated external PostgreSQL database, an OIDC issuer, model endpoint and Secrets. The templates include non-root execution, read-only filesystems, resource limits, startup/readiness/liveness probes and namespace ingress policy. They are deployment starting points, not a claim that a cluster was provisioned or tested in this session.

## IBM DataPower

See [DataPower integration](../deploy/datapower/README.md). The actual gateway requires an entitled, supported deployment and environment-specific policies. Do not substitute a simulator for the product in customer-facing claims.
