package com.danieloh.demo.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** Small explicit policy surface: default deny, no JSON-RPC batches or arbitrary forwarding. */
public final class GatewayPolicy {
    public static final Set<String> READ_TOOLS = Set.of("get_incident", "get_service_metrics", "get_runbook");
    private static final Set<String> METHODS = Set.of("initialize", "notifications/initialized", "ping", "tools/list", "tools/call");
    public record Decision(int status, String reason) { public boolean allowed() { return status == 200; } }

    public Decision evaluate(JsonNode body, boolean writer) {
        if (body == null || !body.isObject() || !body.path("jsonrpc").asText().equals("2.0")
                || !body.path("method").isTextual() || (body.has("id") && !body.get("id").isTextual() && !body.get("id").isNumber()))
            return new Decision(400, "INVALID_JSON_RPC");
        String method = body.path("method").asText();
        if (!METHODS.contains(method)) return new Decision(403, "METHOD_NOT_ALLOWED");
        if (method.equals("tools/call")) {
            String tool = body.path("params").path("name").asText();
            if (!(writer ? tool.equals("create_followup") : READ_TOOLS.contains(tool)))
                return new Decision(403, "TOOL_NOT_ALLOWED");
            var args = body.path("params").path("arguments");
            String field = writer ? "runId" : "incidentId";
            String pattern = writer ? "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" : "INC-[0-9]{4}";
            if (!args.isObject() || args.size() != 1 || !args.path(field).isTextual() || !args.path(field).asText().matches(pattern))
                return new Decision(400, "INVALID_TOOL_ARGUMENTS");
        }
        return new Decision(200, "POLICY_PASSED");
    }
}
