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
