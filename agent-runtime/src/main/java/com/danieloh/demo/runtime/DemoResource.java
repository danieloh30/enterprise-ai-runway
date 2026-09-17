package com.danieloh.demo.runtime;

import com.danieloh.demo.shared.Database;
import com.fasterxml.jackson.databind.JsonNode;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RunOnVirtualThread
public class DemoResource {
    @Inject Database db;
    @Inject RunService runs;
    @Inject GatewayClient gateway;
    @ConfigProperty(name="runway.model") String model;
    @ConfigProperty(name="runway.llm-base-url") String modelUrl;
    @ConfigProperty(name="quarkus.langchain4j.openai.api-key") String modelKey;
    @ConfigProperty(name="runway.gateway-kind") String gatewayKind;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    public record StartRequest(@NotBlank @Pattern(regexp="INC-[0-9]{4}") String incidentId,
        @NotBlank @Pattern(regexp="live|rehearsal") String mode, @NotBlank @Size(max=2000) String prompt) {}
    public record BlueprintRequest(@NotBlank @Size(max=2000) String prompt) {}

    @GET @Path("/status")
    public Map<String,Object> status() {
        boolean modelReady=false;
        try {
            var response=http.send(HttpRequest.newBuilder(URI.create(modelUrl+"/models")).timeout(Duration.ofSeconds(3))
                .header("Authorization","Bearer "+modelKey).GET().build(),HttpResponse.BodyHandlers.ofString());
            modelReady=response.statusCode()==200 && response.body().contains("\""+model+"\"");
        } catch(Exception e) {if(e instanceof InterruptedException)Thread.currentThread().interrupt();}
        recover();
        return Map.of("quarkus","3.39.3","java",Runtime.version().feature(),"model",model,"modelReady",modelReady,
            "gateway",gatewayKind,"database",!db.query("SELECT to_json(1)").isEmpty(),"data","Seeded enterprise incidents");
    }
    @GET @Path("/incidents") public List<JsonNode> incidents() {
        return db.query("SELECT json_build_object('id',id,'service',service,'severity',severity,'summary',summary,'status',status) FROM incidents ORDER BY id");
    }
    @POST @Path("/blueprint") public Map<String,Object> blueprint(@Valid @NotNull BlueprintRequest request) {
        String config="quarkus.langchain4j.mcp.enterprise.transport-type=streamable-http\nquarkus.langchain4j.mcp.enterprise.url=${MCP_GATEWAY_URL}/mcp\nquarkus.langchain4j.mcp.enterprise.header.Authorization=Bearer ${GATEWAY_READ_KEY}\n";
        String java="""
            @RegisterAiService(maxToolCallingRoundTrips = 4,
                chatMemoryProviderSupplier = RegisterAiService.NoChatMemoryProviderSupplier.class)
            public interface InvestigatorAgent {
                @SystemMessage("Investigate using verified evidence. Human approval is required for actions.")
                @McpToolBox("enterprise")
                String investigate(@UserMessage String message);
            }
            """;
        String bob="In this Java 25 / Quarkus 3.39.3 Maven project, inspect the three services and existing tests. "+request.prompt()
            +" Keep all agent tool calls behind the MCP gateway. Expose only parameterized, bounded read tools to the investigator. Preserve separate human approval and idempotency for writes. Add meaningful tests, update README, and explain the changes before applying them. Do not use real credentials or bypass gateway policy.";
        return Map.of("generator","Deterministic project templates — use the included prompt in IBM Bob for AI code generation",
            "prompt",request.prompt(),"bobPrompt",bob,"files",Map.of("application.properties",config,"InvestigatorAgent.java",java),
            "topology",Map.of("nodes",List.of("IBM Bob","Quarkus agents","Policy gateway","MCP tools","PostgreSQL"),
                "runtimePath",List.of("Quarkus agents","Policy gateway","MCP tools","PostgreSQL"),"transport","MCP Streamable HTTP","writePolicy","human approval required"));
    }
    @POST @Path("/runs") public Response start(@Valid @NotNull StartRequest request) {
        UUID id=runs.start(request.incidentId(),request.mode(),request.prompt());
        return Response.accepted(Map.of("id",id)).header("Location","/api/runs/"+id).build();
    }
    @GET @Path("/runs") public List<JsonNode> list() {recover();return db.query("SELECT row_to_json(r) FROM (SELECT id,incident_id,mode,status,created_at FROM runs ORDER BY created_at DESC LIMIT 25) r");}
    @GET @Path("/runs/{id}") public JsonNode get(@PathParam("id") UUID id) {recover();return runs.get(id);}
    @POST @Path("/runs/{id}/approve") public JsonNode approve(@PathParam("id") UUID id) {return runs.approve(id);}
    @POST @Path("/runs/{id}/reject") public Map<String,String> reject(@PathParam("id") UUID id) {runs.reject(id);return Map.of("status","REJECTED");}
    @POST @Path("/probes/{kind}") public GatewayClient.Reply probe(@PathParam("kind") String kind) {
        if(!Set.of("unauthorized","forbidden-tool","invalid-arguments").contains(kind))throw new BadRequestException("Unknown probe");
        return gateway.probe(kind);
    }
    @GET @Path("/audit") public List<JsonNode> audit() {return db.query("SELECT row_to_json(a) FROM (SELECT * FROM gateway_audit ORDER BY id DESC LIMIT 80) a");}
    @GET @Path("/followups") public List<JsonNode> tasks() {return db.query("SELECT row_to_json(f) FROM (SELECT * FROM followups ORDER BY created_at DESC LIMIT 25) f");}

    private void recover() {
        db.update("UPDATE runs SET status='FAILED',report='Interrupted or timed out. Start a new run.',updated_at=now() WHERE status='RUNNING' AND created_at < now()-interval '160 seconds'");
        db.update("UPDATE runs SET status='APPROVED',updated_at=now() WHERE status='APPROVING' AND EXISTS(SELECT 1 FROM followups f WHERE f.run_id=runs.id)");
        db.update("UPDATE runs SET status='AWAITING_APPROVAL',approval_at=NULL,updated_at=now() WHERE status='APPROVING' AND updated_at < now()-interval '30 seconds'");
    }
}
