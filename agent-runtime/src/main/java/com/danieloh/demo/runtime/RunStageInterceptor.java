package com.danieloh.demo.runtime;

import dev.langchain4j.agentic.agent.ChatMessagesAccess;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.util.UUID;

@RunStage
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class RunStageInterceptor {
    @Inject RunService runs;

    @AroundInvoke
    Object recordStage(InvocationContext context) throws Exception {
        String actor=switch(context.getMethod().getName()) {
            case "investigate" -> "investigator";
            case "review" -> "reviewer";
            default -> null;
        };
        if(actor==null) return context.proceed();
        UUID id=(UUID)context.getParameters()[0];
        // Agentic supplies its invocation memory ID separately from our persisted run UUID.
        Object memoryId=context.getParameters()[1];
        boolean completed=false;
        try {
            // Interceptor failures propagate; observational AgentListener failures are swallowed.
            runs.checkRunning(id);
            runs.event(id,actor,"STARTED",actor.equals("investigator")
                ? "Investigator is selecting read-only MCP tools"
                : "Reviewer is checking the investigation against independent observations");
            Object result=context.proceed();
            runs.checkRunning(id);
            runs.event(id,actor,"COMPLETED",actor.equals("investigator")
                ? "Investigation complete; sending observations to independent reviewer"
                : "Risk review complete; awaiting a human decision");
            completed=true;
            return result;
        } finally {
            // Suppliers create an independent in-memory store per run and per agent.
            // Evict on success, failure, and cancellation so conversations cannot survive the call.
            ((ChatMemoryAccess)context.getTarget()).evictChatMemory(memoryId);
            // Successful workflows consume this event; failed invocations never reach that step.
            if(!completed) ((ChatMessagesAccess)context.getTarget()).removeLastResponseEvent(memoryId);
        }
    }
}
