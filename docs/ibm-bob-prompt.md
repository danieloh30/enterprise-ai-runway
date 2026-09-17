# IBM Bob live coding prompt

Open this repository in IBM Bob. This is a real IDE handoff, not a Bob API integration.

> Inspect this Quarkus Maven reactor. Use the Java release and Quarkus platform version configured in the root `pom.xml`, and resolve extension versions from the project's POMs and imported BOMs. Work with those pinned versions and use `./mvnw` for build and verification commands. Add a read-only `get_change_summary` MCP tool that returns only the `deployment` and `change` fields for an existing incident, using a parameterized query. Extend the gateway's read-tool allowlist and strict incident ID validation, and update the investigator instructions to use the new tool when helpful. Preserve the private backend credential, human approval checks and follow-up idempotency. Do not expose arbitrary SQL or change infrastructure. Add tests proving authorized reads succeed and write escalation still fails. Update README. Show me the planned files and diff before applying the change. Never include credentials in source or output.

For the 15-minute talk, keep this enhancement on a separate branch. The base demo is already complete. Validate the new MCP schema and gateway discovery filtering before switching your live presentation to the modified build.
