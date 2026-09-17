package com.danieloh.demo.gateway;

import com.danieloh.demo.shared.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.micrometer.core.instrument.MeterRegistry;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Path("/mcp")
@RunOnVirtualThread
public class GatewayResource {
    @Inject ObjectMapper mapper;
    @Inject Database db;
    @Inject MeterRegistry metrics;
    @ConfigProperty(name="runway.read-key") String readKey;
    @ConfigProperty(name="runway.write-key") String writeKey;
    @ConfigProperty(name="runway.backend-key") String backendKey;
    @ConfigProperty(name="runway.backend-url") String backendUrl;
    @ConfigProperty(name="runway.rate-limit") int rateLimit;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private static final Window READ_WINDOW = new Window(), WRITE_WINDOW = new Window();
    private final GatewayPolicy policy = new GatewayPolicy();

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response forward(String raw, @HeaderParam("Authorization") String authorization,
            @HeaderParam("Origin") String origin, @HeaderParam("Mcp-Session-Id") String session,
            @HeaderParam("Mcp-Protocol-Version") String protocol) {
        UUID requestId = UUID.randomUUID();
        boolean reader = Secrets.matches(authorization, "Bearer " + readKey);
        boolean writer = Secrets.matches(authorization, "Bearer " + writeKey);
        String principal = writer ? "approver" : reader ? "agent" : "anonymous";
        JsonNode body;
        try { body = mapper.readTree(raw); }
        catch (Exception e) { body = mapper.createObjectNode(); }
        String method = bounded(body == null ? "" : body.path("method").asText(),64);
        String tool = bounded(body == null ? "" : body.path("params").path("name").asText(),80);
        if (!reader && !writer) return deny(requestId,principal,method,tool,401,"INVALID_CREDENTIAL");
        // Browser clients use the SPA's backend; direct cross-origin MCP access is never allowed.
        if (origin != null) return deny(requestId,principal,method,tool,403,"BROWSER_ORIGIN_DENIED");
        if (!(writer ? WRITE_WINDOW : READ_WINDOW).take(rateLimit)) return deny(requestId,principal,method,tool,429,"RATE_LIMIT_EXCEEDED");
        var decision = policy.evaluate(body, writer);
        if (!decision.allowed()) return deny(requestId,principal,method,tool,decision.status(),decision.reason());
        // Fail closed if the audit store is unavailable, before invoking any backend operation.
        audit(requestId,principal,method,tool,"ALLOW","POLICY_PASSED",200);
        try {
            var builder = HttpRequest.newBuilder(URI.create(backendUrl + "/mcp"))
                .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + backendKey)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(raw));
            if (session != null) builder.header("Mcp-Session-Id",session);
            if (protocol != null) builder.header("Mcp-Protocol-Version",protocol);
            var upstream = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String contentType = upstream.headers().firstValue("content-type").orElse("application/json");
            String responseBody = upstream.body();
            if (method.equals("tools/list") && upstream.statusCode() == 200) responseBody = filterTools(responseBody, writer, contentType);
            var response = Response.status(upstream.statusCode()).type(contentType).entity(responseBody).header("X-Request-Id",requestId);
            upstream.headers().firstValue("Mcp-Session-Id").ifPresent(v -> response.header("Mcp-Session-Id",v));
            return response.build();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return deny(requestId,principal,method,tool,502,"BACKEND_UNAVAILABLE");
        }
    }

    String filterTools(String text, boolean writer, String contentType) throws Exception {
        if (contentType.contains("text/event-stream")) {
            var filtered = new StringBuilder();
            for (String line : text.split("\n", -1)) {
                if (line.startsWith("data:")) line = "data: " + filterJson(mapper.readTree(line.substring(5).trim()),writer);
                filtered.append(line).append('\n');
            }
            return filtered.toString();
        }
        return filterJson(mapper.readTree(text),writer).toString();
    }
    private JsonNode filterJson(JsonNode node, boolean writer) {
        if (node.path("result").path("tools") instanceof ArrayNode tools) {
            for (int i=tools.size()-1;i>=0;i--) {
                String name=tools.get(i).path("name").asText();
                if (!(writer ? name.equals("create_followup") : GatewayPolicy.READ_TOOLS.contains(name))) tools.remove(i);
            }
        }
        return node;
    }
    private Response deny(UUID id,String principal,String method,String tool,int status,String reason) {
        audit(id,principal,method,tool,"DENY",reason,status);
        return Response.status(status).type(MediaType.APPLICATION_JSON).header("X-Request-Id",id)
            .header("Retry-After", status==429 ? "60" : "0")
            .entity(Map.of("error",reason,"requestId",id)).build();
    }
    private void audit(UUID id,String principal,String method,String tool,String decision,String reason,int status) {
        db.update("INSERT INTO gateway_audit(request_id,principal,method,tool,decision,reason,status) VALUES(?,?,?,?,?,?,?)",
            id,principal,method,tool,decision,reason,status);
        metrics.counter("runway.gateway.requests","decision",decision,"principal",principal).increment();
    }
    private String bounded(String value,int length) { return value.substring(0,Math.min(length,value.length())); }
    static final class Window {
        private long minute; private int count;
        synchronized boolean take(int limit) {
            long current=System.currentTimeMillis()/60000;
            if (current!=minute) {minute=current;count=0;}
            return ++count<=limit;
        }
    }
}
