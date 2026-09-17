# Kubernetes deployment templates

`services.yaml` contains three one-replica Deployments, ClusterIP Services and ingress NetworkPolicies in a restricted namespace. These manifests have not been deployed to a real cluster. No public ingress is supplied; expose the app through your approved TLS ingress after identity and management-endpoint restrictions are configured. Port forwarding can be used for a private demonstration.

1. Build the root Maven reactor. Build/push an image for each module using the root `Containerfile` and `--build-arg MODULE=<module>`. Replace the example image references with your registry's tested digests. The repository push does not publish container images.
2. Provision an external PostgreSQL database. Apply Flyway migrations from `mcp-tools/src/main/resources/db/migration` using a migration identity before starting pods. Application pods disable migration-at-start. Use separate least-privilege database roles as described in `docs/production.md`.
3. Create the namespace and three Secrets from your secret manager. Do not commit plaintext Secrets. All three need `DB_URL`, `DB_USER`, `DB_PASSWORD`.
4. `runway-runtime` additionally needs `DEMO_API_KEY` (a disabled/unusable placeholder if OIDC is enabled), `GATEWAY_READ_KEY`, `GATEWAY_WRITE_KEY`, `MCP_GATEWAY_URL=http://runway-gateway:8091`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_API_KEY`, `OIDC_ENABLED=true`, `OIDC_AUTH_SERVER_URL`, `OIDC_CLIENT_ID`. Set `GATEWAY_KIND` to the actual gateway arrangement.
5. `runway-gateway` additionally needs `GATEWAY_READ_KEY`, `GATEWAY_WRITE_KEY`, `BACKEND_KEY`, `MCP_BACKEND_URL=http://runway-tools:8092`.
6. `runway-tools` additionally needs `BACKEND_KEY`.
7. Install a NetworkPolicy-capable CNI. Apply `services.yaml`, inspect rollouts and health. Use TLS/mTLS or your service mesh for internal traffic before production use.

```bash
kubectl apply -f deploy/kubernetes/services.yaml
kubectl -n enterprise-ai-runway rollout status deployment/runway-tools
kubectl -n enterprise-ai-runway rollout status deployment/runway-gateway
kubectl -n enterprise-ai-runway rollout status deployment/runway-agent
kubectl -n enterprise-ai-runway port-forward service/runway-agent 8090:8090
```

Supply a suitable ingress allow policy for your ingress controller before exposing the UI. Add explicitly scoped ingress for an actual DataPower deployment when it calls the tool policy service. Keep `/q/*` management paths private; the sample application role filter covers `/api/*`, not the management surface. Egress restrictions are environment-specific and not included here; allow only the model, database, identity and required internal endpoints in your production policy.

The rate limiter and active execution slots are per process. Keep one gateway replica until rate limiting is moved to DataPower or a shared limiter. A persistent worker queue is needed for transparent execution resumption or scalable workload admission.
