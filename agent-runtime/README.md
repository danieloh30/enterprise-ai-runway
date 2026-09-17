# agent-runtime

Part of [Enterprise AI Runway](../README.md). Run all services in Quarkus Dev Mode from the repository root with `./demo.sh up`.

## Source map

Java sources live under `src/main/java/com/danieloh/demo/runtime/`:

```text
runtime/
├── agents/
│   ├── InvestigatorAgent.java
│   └── ReviewerAgent.java
├── workflow/
│   ├── InvestigationWorkflow.java
│   ├── RunService.java
│   ├── RunStage.java
│   └── RunStageInterceptor.java
├── api/
│   ├── DemoResource.java
│   └── ServiceErrorMapper.java
├── security/
│   ├── ApiAuthentication.java
│   └── LocalPresenterSession.java
└── gateway/
    └── GatewayClient.java
```

- [agents](src/main/java/com/danieloh/demo/runtime/agents): the investigator and reviewer `@Agent` interfaces, prompts, and memory suppliers.
- [workflow](src/main/java/com/danieloh/demo/runtime/workflow): the `@SequenceAgent` orchestration, run lifecycle, stage events, conversation cleanup, and human decisions.
- [api](src/main/java/com/danieloh/demo/runtime/api): REST endpoints, blueprint templates, and error responses.
- [security](src/main/java/com/danieloh/demo/runtime/security): presenter/OIDC authorization and local browser sessions.
- [gateway](src/main/java/com/danieloh/demo/runtime/gateway): the client for gateway-protected MCP calls and policy probes.

Tests under `src/test/java/com/danieloh/demo/runtime/` follow the same packages. Shared test fixtures live in `support/`. Runtime configuration stays in `src/main/resources/application.properties`, and the SPA stays in `src/main/resources/META-INF/resources/`.

See the root README for configuration, API endpoints, the 15-minute script and verification commands. The shared root `Containerfile` builds the tested Java 25 JVM image for this module.
