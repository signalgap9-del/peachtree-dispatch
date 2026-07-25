package com.atmospath.platform.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.atmospath.platform.llm.intent.Intent;
import com.atmospath.platform.llm.intent.IntentClassificationService;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class IntentClassificationServiceTests {

    private final LlmClient llmClient = mock(LlmClient.class);
    private final PromptTemplateService templates = new PromptTemplateService();
    private final IntentClassificationService service = new IntentClassificationService(llmClient, templates);

    @Test
    void llmResponseMapsToIntent() {
        when(llmClient.complete(anyList())).thenReturn("route_plan");

        assertThat(service.classify("Get me from Denver to Boulder")).isEqualTo(Intent.ROUTE_PLAN);
    }

    @Test
    void llmPromptContainsUserMessage() {
        when(llmClient.complete(anyList())).thenReturn("explain");

        service.classify("Why is I-70 risky today?");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).complete(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(1).content()).contains("Why is I-70 risky today?");
    }

    @Test
    void llmResponseParsingToleratesNoise() {
        when(llmClient.complete(anyList())).thenReturn("  Fleet Optimize.\n");

        assertThat(service.classify("Plan deliveries for three vans")).isEqualTo(Intent.FLEET_OPTIMIZE);
    }

    @Test
    void unexpectedLlmResponseMapsToUnknown() {
        when(llmClient.complete(anyList())).thenReturn("reschedule");

        assertThat(service.classify("Push my meeting to Friday")).isEqualTo(Intent.UNKNOWN);
    }

    @Test
    void llmFailureFallsBackToKeywordRoutePlan() {
        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("Bedrock unavailable"));

        assertThat(service.classify("Get directions from Denver to Boulder")).isEqualTo(Intent.ROUTE_PLAN);
    }

    @Test
    void llmFailureFallsBackToKeywordModify() {
        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("Bedrock unavailable"));

        assertThat(service.classify("what if we leave an hour later")).isEqualTo(Intent.MODIFY);
    }

    @Test
    void llmFailureFallsBackToKeywordCompare() {
        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("Bedrock unavailable"));

        assertThat(service.classify("which is better, the northern or southern option")).isEqualTo(Intent.COMPARE);
    }

    @Test
    void llmFailureFallsBackToKeywordFleetOptimize() {
        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("Bedrock unavailable"));

        assertThat(service.classify("assign deliveries to my fleet")).isEqualTo(Intent.FLEET_OPTIMIZE);
    }

    @Test
    void llmFailureFallsBackToExplainByDefault() {
        when(llmClient.complete(anyList())).thenThrow(new IllegalStateException("Bedrock unavailable"));

        assertThat(service.classify("why is this road risky today")).isEqualTo(Intent.EXPLAIN);
    }
}
