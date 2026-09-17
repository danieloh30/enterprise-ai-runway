package com.danieloh.demo.runtime.workflow;

import com.danieloh.demo.runtime.agents.InvestigatorAgent;
import com.danieloh.demo.runtime.agents.ReviewerAgent;
import com.danieloh.demo.runtime.support.AgentMcpTestResource;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agentic.agent.ChatMessagesAccess;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.LangChain4jManaged;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.memory.ChatMemoryAccess;
import io.quarkus.test.InjectMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.MockitoConfig;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@QuarkusTest
@QuarkusTestResource(value=AgentMcpTestResource.class,restrictToAnnotatedClass=true)
class InvestigationWorkflowTest {
    @Inject InvestigationWorkflow workflow;
    @Inject InvestigatorAgent investigator;
    @Inject ReviewerAgent reviewer;
    @InjectMock @MockitoConfig(convertScopes=true) ChatModel model;
    @InjectMock RunService runs;

    @BeforeEach
    void setup() {reset(model,runs);AgentMcpTestResource.resetCalls();}

    @Test
    void sequenceCallsMcpThenReviewsIndependentEvidenceWithoutTools() {
        var memoryId=new AtomicReference<Object>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            memoryId.set(LangChain4jManaged.current(AgenticScope.class).memoryId());
            assertNotNull(((ChatMemoryAccess)investigator).getChatMemory(memoryId.get()));
            return toolResponse(1);
        }).thenReturn(textResponse("Suspected database saturation"),textResponse("Reviewed report"));
        UUID id=UUID.randomUUID();
        assertEquals("Reviewed report",workflow.investigate(id,"Investigate INC-2042","Independent observation: p95 850ms"));
        assertNoRetainedMemory(memoryId.get());
        assertEquals(List.of("get_incident"),AgentMcpTestResource.calls());
        var requests=ArgumentCaptor.forClass(ChatRequest.class);
        verify(model,times(3)).chat(requests.capture());
        assertFalse(requests.getAllValues().getFirst().parameters().toolSpecifications().isEmpty());
        ChatRequest review=requests.getAllValues().getLast();
        assertTrue(review.parameters().toolSpecifications()==null || review.parameters().toolSpecifications().isEmpty());
        assertTrue(userText(review).contains("Independent observation: p95 850ms"));
        assertTrue(userText(review).contains("Suspected database saturation"));
        var order=inOrder(runs);
        order.verify(runs).event(eq(id),eq("investigator"),eq("STARTED"),anyString());
        order.verify(runs).event(eq(id),eq("investigator"),eq("COMPLETED"),anyString());
        order.verify(runs).event(eq(id),eq("reviewer"),eq("STARTED"),anyString());
        order.verify(runs).event(eq(id),eq("reviewer"),eq("COMPLETED"),anyString());
        verify(runs,never()).approve(any());
    }

    @Test
    void rejectsTheFourthToolCallInOneResponse() {
        when(model.chat(any(ChatRequest.class))).thenReturn(toolResponse(4));
        assertThrows(RuntimeException.class,() -> workflow.investigate(UUID.randomUUID(),"Investigate INC-2042","Evidence"));
        assertEquals(3,AgentMcpTestResource.calls().size());
        verify(model,times(1)).chat(any(ChatRequest.class));
        verify(runs,never()).event(any(),eq("reviewer"),anyString(),anyString());
    }

    @Test
    void repeatedToolRequestsStopAtFourRounds() {
        when(model.chat(any(ChatRequest.class))).thenReturn(toolResponse(1));
        assertThrows(RuntimeException.class,() -> workflow.investigate(UUID.randomUUID(),"Investigate INC-2042","Evidence"));
        assertEquals(4,AgentMcpTestResource.calls().size());
        verify(runs,never()).event(any(),eq("reviewer"),anyString(),anyString());
    }

    @Test
    void expiredRunStopsBeforeReviewer() {
        UUID id=UUID.randomUUID();
        var memoryId=new AtomicReference<Object>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            memoryId.set(LangChain4jManaged.current(AgenticScope.class).memoryId());
            doThrow(new IllegalStateException("Run no longer active")).when(runs).checkRunning(id);
            return textResponse("Investigator finished after the deadline");
        });
        assertThrows(RuntimeException.class,() -> workflow.investigate(id,"Investigate INC-2042","Evidence"));
        assertNoRetainedMemory(memoryId.get());
        verify(model,times(1)).chat(any(ChatRequest.class));
        verify(runs,never()).event(any(),eq("reviewer"),anyString(),anyString());
    }

    @Test
    void runsDoNotShareFindingsOrConversationHistory() {
        when(model.chat(any(ChatRequest.class))).thenReturn(textResponse("First confidential finding"),textResponse("First report"),
            textResponse("Second finding"),textResponse("Second report"));
        assertEquals("First report",workflow.investigate(UUID.randomUUID(),"First incident","First evidence"));
        assertEquals("Second report",workflow.investigate(UUID.randomUUID(),"Second incident","Second evidence"));
        var requests=ArgumentCaptor.forClass(ChatRequest.class);
        verify(model,times(4)).chat(requests.capture());
        for(ChatRequest request:requests.getAllValues().subList(2,4)) {
            assertFalse(request.messages().toString().contains("First"));
        }
        assertTrue(userText(requests.getAllValues().getLast()).contains("Second finding"));
        assertTrue(userText(requests.getAllValues().getLast()).contains("Second evidence"));
    }

    @Test
    void modelFailureEvictsMemoryBeforeTheRunIdIsReused() {
        UUID id=UUID.randomUUID();
        var memoryId=new AtomicReference<Object>();
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            memoryId.set(LangChain4jManaged.current(AgenticScope.class).memoryId());
            assertNotNull(((ChatMemoryAccess)investigator).getChatMemory(memoryId.get()));
            throw new IllegalStateException("Model unavailable");
        });
        assertThrows(RuntimeException.class,() -> workflow.investigate(id,"First confidential incident","First evidence"));
        assertNoRetainedMemory(memoryId.get());
        reset(model);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            memoryId.set(LangChain4jManaged.current(AgenticScope.class).memoryId());
            return textResponse("Second finding");
        }).thenReturn(textResponse("Second report"));
        assertEquals("Second report",workflow.investigate(id,"Second incident","Second evidence"));
        assertNoRetainedMemory(memoryId.get());
        var requests=ArgumentCaptor.forClass(ChatRequest.class);
        verify(model,times(2)).chat(requests.capture());
        for(ChatRequest request:requests.getAllValues()) {
            assertFalse(request.messages().toString().contains("First"));
        }
    }

    @Test
    void concurrentRunsKeepTheirEvidenceAndFindingsSeparate() throws Exception {
        var investigatorsStarted=new CountDownLatch(2);
        when(model.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            ChatRequest request=invocation.getArgument(0);
            String text=userText(request);
            if(text.startsWith("Investigate")) {
                investigatorsStarted.countDown();
                assertTrue(investigatorsStarted.await(5,TimeUnit.SECONDS));
                return textResponse(text.contains("alpha") ? "alpha finding" : "beta finding");
            }
            if(text.contains("alpha evidence")) {
                assertTrue(text.contains("alpha finding"));
                assertFalse(text.contains("beta"));
                return textResponse("alpha report");
            }
            assertTrue(text.contains("beta evidence"));
            assertTrue(text.contains("beta finding"));
            assertFalse(text.contains("alpha"));
            return textResponse("beta report");
        });
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var alpha=workers.submit(() -> workflow.investigate(UUID.randomUUID(),"Investigate alpha","alpha evidence"));
            var beta=workers.submit(() -> workflow.investigate(UUID.randomUUID(),"Investigate beta","beta evidence"));
            assertEquals("alpha report",alpha.get(10,TimeUnit.SECONDS));
            assertEquals("beta report",beta.get(10,TimeUnit.SECONDS));
        }
    }

    private void assertNoRetainedMemory(Object id) {
        assertNotNull(id);
        assertNull(((ChatMemoryAccess)investigator).getChatMemory(id));
        assertNull(((ChatMemoryAccess)reviewer).getChatMemory(id));
        assertNull(((ChatMessagesAccess)investigator).lastChatRequest(id));
        assertNull(((ChatMessagesAccess)reviewer).lastChatRequest(id));
    }

    private static ChatResponse textResponse(String text) {return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();}

    private static ChatResponse toolResponse(int count) {
        var tools=IntStream.range(0,count).mapToObj(i -> ToolExecutionRequest.builder().id("call-"+i).name("get_incident")
            .arguments("{\"incidentId\":\"INC-2042\"}").build()).toList();
        return ChatResponse.builder().aiMessage(AiMessage.from(tools)).build();
    }

    private static String userText(ChatRequest request) {
        return request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
            .map(UserMessage::singleText).reduce("",(left,right) -> left+right);
    }
}
