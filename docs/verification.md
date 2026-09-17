# Verification record

Verified locally on 2026-09-16 on an Apple Silicon Mac with Temurin Java 25.0.1, Podman 5.4.2, ARM64 Linux application containers, PostgreSQL 17.11, Quarkus 3.39.3 and native Ollama `llama3.2:latest`.

- `./mvnw -B verify`: passed, 12 Java tests, zero failures/errors/skips; all reactor modules packaged.
- `npm run test:ui`: passed, 2 Chromium tests. Desktop blueprint, gateway probes, rehearsal investigation, approval and history; mobile navigation and overflow check.
- `./demo.sh smoke`: passed against real packaged services and PostgreSQL.
- `SMOKE_MODE=live ./demo.sh smoke`: passed with actual investigator/reviewer model execution and MCP tools, including on the final PostgreSQL 17.11 stack.
- Concurrent approvals and repeated requests created exactly one follow-up for a run. Declined runs could not subsequently be approved.
- Final container image inspection reported `linux/arm64`; runtime and database logs confirmed successful startup.
- Frontend JavaScript, Bash launcher and Python smoke runner passed syntax checks. Dependency installation reported no npm vulnerabilities.

The verification process caught an API authentication path-normalization bug, corrected it, and verified the regression with HTTP-level tests before publication.

The native laptop uses a local policy simulator. No actual IBM DataPower appliance, IBM Bob IDE interaction, Kubernetes cluster, organization OIDC issuer, load test or model safety evaluation was available as part of this verification. Deployment references document the required integration work. The SPA's blueprint generation is explicitly template based; rehearsal mode is explicitly non-LLM.

## OpenAI defaults and dependency automation

The default provider was subsequently changed to OpenAI `gpt-4.1-mini`, with server-side `OPENAI_API_KEY` configuration and an optional `LLM_API_KEY` override. `./mvnw -B verify` passed with 13 Java tests, including rejection of live requests without a model credential and continued acceptance of rehearsal requests. No OpenAI key was available in the verification environment, so a live OpenAI request has **not** been verified. The earlier live execution result above used Ollama.

The actual inline Dependabot merge script passed 19 Node.js regression cases: the eligible PR path plus rejection of failed/superseded runs, untested heads, forks, incorrect authors/branches, non-POM changes and other ineligible cases. All workflow YAML files parsed, and frontend/launcher syntax checks passed. The workflow is configured to merge only after a successful Verify run for the current PR commit; a real Dependabot upgrade has not yet exercised the merge end to end.

All running containers were stopped at the presenter's request. These follow-up checks did not start any containers.

## Automatic local browser connection

`./mvnw -B verify` passed with 19 Java tests after adding temporary local presenter sessions. HTTP-level tests cover session issuance, API authentication, disabled packaged defaults, and rejected cross-origin/simple requests. Unit tests cover OIDC disabling the feature, loopback hostname restrictions and invalidation after a runtime restart.

`npm run test:access` passed four isolated Chromium tests covering automatic connection/reload, manual-mode fallback, visible backend failure and renewal after an expired session. These browser tests use mocked API responses and need neither containers nor an OpenAI key. The full PostgreSQL/model workflow was not rerun for this access change. The local launcher and Quarkus Dev Mode enable automatic connection; other packaged deployments require explicit opt-in.


## Quarkus Dev Mode launcher

Verified on 2026-09-17 on the same Apple Silicon Mac after changing `./demo.sh up` to foreground Dev Mode:

- All three services reported the dev profile and live coding, with separate HTTP/debug ports and labeled logs in one terminal.
- The old PostgreSQL container was replaced with a loopback port while retaining `runway-data`. Restarting the launcher retained the earlier smoke-test follow-up.
- `./demo.sh smoke` and `SMOKE_MODE=live ./demo.sh smoke` both passed against the dev-mode services. The live check used the configured OpenAI endpoint and actual investigator/reviewer execution; this supersedes the earlier unverified OpenAI result above.
- Java source changes were compiled and served without restarting the launcher. The temporary verification endpoint was removed afterward.
- `npm run test:ui`: both Chromium tests passed against Dev Mode, including automatic local connection, the complete rehearsal/approval flow, history, and mobile navigation.
- Both `./demo.sh down` from another terminal and Ctrl+C shut down all three applications and PostgreSQL. Status then showed the launcher stopped and all three HTTP ports closed. A duplicate `up` was rejected while the original launcher remained running.
- `./mvnw -B verify`: 26 Java tests passed and all service packages built.
- Five Python lifecycle tests passed, covering forked child cleanup, unrelated process preservation, startup failure logs, stop requests, and refusing to stop an unowned container. Bash/Python syntax checks and the 19 dependency-merge safeguard tests also passed.

Smoke checks remain optional HTTP checks of the running flow. Dev Mode does not run them automatically. The demo was stopped after verification; its history remains in the database volume.


## Quarkus 3.39.4 and shared configuration

On 2026-09-17, upgraded the platform from 3.39.3 to the latest stable 3.39.4 and consolidated common datasource/HTTP settings into `shared/src/main/resources/META-INF/microprofile-config.properties`.

- `./mvnw -B clean verify`: 26 Java tests passed with zero failures, errors, or skips; all modules packaged with the existing LangChain4j and MCP extension versions.
- All three applications started in Quarkus 3.39.4 Dev Mode with the existing persistent PostgreSQL database.
- Readiness and shared `X-Content-Type-Options`, `Referrer-Policy`, `Cache-Control`, and `X-Frame-Options` headers were verified on all three HTTP services.
- `./demo.sh smoke` passed the actual gateway, MCP, database, approval/retry, rejection, and audit flow after the configuration change. The generated agent workflow tests used a mocked model; live model execution was not repeated for this patch upgrade.
- Only `runway-db` was running in Podman; removing the redundant datasource flags did not start additional database containers. Explicit JDBC URLs select the launcher-managed database and isolated test configuration.
