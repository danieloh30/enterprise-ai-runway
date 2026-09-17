package com.danieloh.demo.runtime;

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
        return result;
    }
}
