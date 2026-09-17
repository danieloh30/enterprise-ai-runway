package com.danieloh.demo.runtime;

import com.fasterxml.jackson.databind.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@ApplicationScoped
public class GatewayClient {
    @Inject ObjectMapper mapper;
    @ConfigProperty(name="runway.gateway-url") String url;
    @ConfigProperty(name="runway.gateway-read-key") String readKey;
    @ConfigProperty(name="runway.gateway-write-key") String writeKey;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public record Reply(int status, JsonNode body, String requestId) {}

    public JsonNode call(String tool, Map<String,String> arguments, boolean write) {
        var reply = request(Map.of("jsonrpc","2.0","id",UUID.randomUUID().toString(),"method","tools/call",
            "params",Map.of("name",tool,"arguments",arguments)), write ? writeKey : readKey);
        if (reply.status()!=200 || reply.body().has("error") || reply.body().path("result").path("isError").asBoolean())
            throw new IllegalStateException("MCP tool failed: " + tool + " (HTTP " + reply.status() + ")");
        var content=reply.body().path("result").path("content");
        if (!content.isArray() || content.isEmpty()) throw new IllegalStateException("MCP returned no tool evidence");
        try { return mapper.readTree(content.get(0).path("text").asText()); }
        catch (Exception e) { throw new IllegalStateException("MCP returned invalid evidence",e); }
    }

    public Reply probe(String kind) {
        return switch(kind) {
            case "unauthorized" -> request(Map.of("jsonrpc","2.0","id",1,"method","ping"),"invalid-credential");
            case "forbidden-tool" -> request(Map.of("jsonrpc","2.0","id",2,"method","tools/call","params",Map.of("name","create_followup","arguments",Map.of("runId",UUID.randomUUID().toString()))),readKey);
            case "invalid-arguments" -> request(Map.of("jsonrpc","2.0","id",3,"method","tools/call","params",Map.of("name","get_incident","arguments",Map.of("incidentId","INC-2042' OR 1=1 --"))),readKey);
            default -> throw new IllegalArgumentException("Unknown probe");
        };
    }

    private Reply request(Object body, String key) {
        try {
            var request=HttpRequest.newBuilder(URI.create(url+"/mcp")).timeout(Duration.ofSeconds(15))
                .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .header("Accept","application/json, text/event-stream").header("Mcp-Protocol-Version","2025-11-25")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            String text=response.body();
            if (response.headers().firstValue("content-type").orElse("").contains("text/event-stream"))
                text=text.lines().filter(l -> l.startsWith("data:")).map(l -> l.substring(5).trim()).filter(l -> !l.isEmpty()).findFirst().orElse("{}");
            return new Reply(response.statusCode(),mapper.readTree(text),response.headers().firstValue("X-Request-Id").orElse("external"));
        } catch (Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Gateway unavailable; inspect service health and configuration",e);
        }
    }
}
