package com.danieloh.demo.runtime.workflow;

import com.danieloh.demo.runtime.gateway.GatewayClient;

import com.danieloh.demo.shared.Database;
import com.fasterxml.jackson.databind.JsonNode;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import org.jboss.logging.Logger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@ApplicationScoped
public class RunService {
    private static final Logger LOG=Logger.getLogger(RunService.class);
    @Inject Database db;
    @Inject GatewayClient gateway;
    @Inject InvestigationWorkflow investigation;
    @Inject MeterRegistry metrics;
    private final Semaphore slots=new Semaphore(2);
    private final ExecutorService workers=Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();

    public UUID start(String incidentId,String mode,String prompt) {
        if(db.query("SELECT row_to_json(i) FROM incidents i WHERE id=?",incidentId).isEmpty()) throw new NotFoundException("Incident not found");
        if(!slots.tryAcquire()) throw new WebApplicationException("Two runs are already active; retry shortly",429);
        UUID id=UUID.randomUUID();
        try {
            db.update("INSERT INTO runs(id,incident_id,mode,status,prompt) VALUES(?,?,?,'RUNNING',?)",id,incidentId,mode,prompt);
            var future=workers.submit(() -> {
                try { execute(id,incidentId,mode,prompt); }
                catch(Exception e) {
                    LOG.warnf("Run %s failed: %s",id,e.getClass().getSimpleName());
                    fail(id,"Execution failed. Verify the model, gateway and tool services, then start a new run.");
                } finally {slots.release();}
            });
            var timeout=deadlines.schedule(() -> {fail(id,"Run exceeded its 150-second deadline. No action was approved.");future.cancel(true);},150,TimeUnit.SECONDS);
            workers.submit(() -> {try {future.get();} catch(Exception ignored) {} finally {timeout.cancel(false);}});
            return id;
        } catch(Exception e) {slots.release();throw e;}
    }

    private void execute(UUID id,String incidentId,String mode,String prompt) {
        event(id,"orchestrator","STARTED",mode.equals("live") ? "Live agent workflow started" : "Rehearsal workflow started — no LLM calls");
        var evidence=new LinkedHashMap<String,JsonNode>();
        for(String tool:List.of("get_incident","get_service_metrics","get_runbook")) {
            checkRunning(id);
            evidence.put(tool,gateway.call(tool,Map.of("incidentId",incidentId),false));
            event(id,"mcp","TOOL_RESULT",tool+" returned verified database evidence");
        }
        String report;
        if(mode.equals("live")) {
            report=investigation.investigate(id,
                "Investigate incident "+incidentId+". Additional user context: "+prompt,db.json(evidence));
        } else {
            report="REHEARSAL — deterministic report, no AI generation.\n\nFinding\n"+evidence.get("get_incident").path("summary").asText()
                +"\n\nEvidence\n"+db.json(evidence.get("get_service_metrics"))
                +"\n\nRecommended follow-up\n"+evidence.get("get_runbook").path("runbook").asText()
                +"\n\nRisk\nA suspected cause requires validation. Approve only the creation of one follow-up task. No infrastructure changes have occurred.";
            event(id,"rehearsal","COMPLETED","Deterministic report assembled from actual MCP responses");
        }
        if(report==null || report.isBlank()) throw new IllegalStateException("Empty report");
        int changed=db.update("UPDATE runs SET status='AWAITING_APPROVAL',report=?,updated_at=now() WHERE id=? AND status='RUNNING'",report.substring(0,Math.min(report.length(),16000)),id);
        if(changed==1) metrics.counter("runway.runs.completed","mode",mode).increment();
    }

    public JsonNode get(UUID id) {
        var rows=db.query("SELECT row_to_json(r) FROM runs r WHERE id=?",id);
        if(rows.isEmpty()) throw new NotFoundException();
        return rows.getFirst();
    }

    public JsonNode approve(UUID id) {
        var run=get(id);
        if(run.path("status").asText().equals("APPROVED")) return followup(id);
        int claimed=db.update("UPDATE runs SET status='APPROVING',approval_at=now(),updated_at=now() WHERE id=? AND status='AWAITING_APPROVAL'",id);
        if(claimed!=1) throw new WebApplicationException("Run is not awaiting approval",409);
        try {
            var task=gateway.call("create_followup",Map.of("runId",id.toString()),true);
            db.update("UPDATE runs SET status='APPROVED',updated_at=now() WHERE id=? AND status='APPROVING'",id);
            return task;
        } catch(Exception e) {
            // A timeout can occur after the tool committed; check the unique run_id before allowing retry.
            var saved=db.query("SELECT row_to_json(f) FROM followups f WHERE run_id=?",id);
            if(!saved.isEmpty()) {db.update("UPDATE runs SET status='APPROVED',updated_at=now() WHERE id=?",id);return saved.getFirst();}
            db.update("UPDATE runs SET status='AWAITING_APPROVAL',approval_at=NULL,updated_at=now() WHERE id=? AND status='APPROVING'",id);
            throw new WebApplicationException("Follow-up not confirmed. Retry approval; duplicate tasks are prevented.",502);
        }
    }
    private JsonNode followup(UUID id) {return db.query("SELECT row_to_json(f) FROM followups f WHERE run_id=?",id).getFirst();}
    public void reject(UUID id) {
        if(db.update("UPDATE runs SET status='REJECTED',updated_at=now() WHERE id=? AND status='AWAITING_APPROVAL'",id)!=1)
            throw new WebApplicationException("Run is not awaiting approval",409);
    }
    void checkRunning(UUID id) {if(Thread.currentThread().isInterrupted() || !get(id).path("status").asText().equals("RUNNING")) throw new IllegalStateException("Run no longer active");}
    void event(UUID id,String actor,String kind,String detail) {
        db.update("UPDATE runs SET events=events || ?::jsonb,updated_at=now() WHERE id=? AND status='RUNNING'",
            db.json(List.of(Map.of("at",Instant.now().toString(),"actor",actor,"kind",kind,"detail",detail))),id);
    }
    private void fail(UUID id,String message) {db.update("UPDATE runs SET status='FAILED',report=?,updated_at=now() WHERE id=? AND status='RUNNING'",message,id);}
    @PreDestroy void stop() {workers.shutdownNow();deadlines.shutdownNow();}
}
