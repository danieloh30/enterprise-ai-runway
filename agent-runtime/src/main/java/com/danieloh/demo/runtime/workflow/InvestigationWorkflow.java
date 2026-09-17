package com.danieloh.demo.runtime.workflow;

import com.danieloh.demo.runtime.agents.InvestigatorAgent;
import com.danieloh.demo.runtime.agents.ReviewerAgent;

import dev.langchain4j.agentic.declarative.SequenceAgent;
import dev.langchain4j.service.V;
import java.util.UUID;

public interface InvestigationWorkflow {
    @SequenceAgent(name="investigation", outputKey="report",
        subAgents={InvestigatorAgent.class, ReviewerAgent.class})
    String investigate(@V("runId") UUID runId, @V("message") String message, @V("evidence") String evidence);
}
