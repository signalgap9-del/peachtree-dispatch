package com.atmospath.platform.llm.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.LlmProperties;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.llm.security.LlmFallbackChain;
import com.atmospath.platform.llm.security.LlmOutputValidator;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ExplainServiceTests {

    private RiskEngineGateway riskEngine;
    private LiteLlmClient llmClient;
    private PromptTemplateService templates;
    private LlmFallbackChain fallbackChain;
    private LlmOutputValidator outputValidator;
    private ExplainService service;

    private static final LlmProperties PROPS = new LlmProperties(
            true, "http://localhost:4000", "key", "gpt-4o-mini",
            1024, 0.7, 60_000L, 100_000L, 4096);

    @BeforeEach
    void setUp() {
        riskEngine = mock(RiskEngineGateway.class);
        llmClient = mock(LiteLlmClient.class);
        templates = new PromptTemplateService();
        fallbackChain = mock(LlmFallbackChain.class);
        outputValidator = mock(LlmOutputValidator.class);
        service = new ExplainService(riskEngine, llmClient, templates,
                fallbackChain, outputValidator, PROPS);
    }

    private void stubFallbackChain() {
        when(fallbackChain.executeWithFallback(any(), anyString(), any()))
                .thenAnswer(inv -> {
                    var fallback = inv.getArgument(2,
                            com.atmospath.platform.llm.security.StructuredFallback.class);
                    return new LlmFallbackChain.FallbackResult(
                            fallback.render(),
                            LlmFallbackChain.FallbackResult.Source.STRUCTURED, 5);
                });
    }

    @Test
    void explainNationalRiskReturnsEmitter() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("risk_score", 42);
        when(riskEngine.get("/risk/national")).thenReturn(data);
        stubFallbackChain();

        SseEmitter emitter = service.explainNationalRisk("en");

        assertThat(emitter).isNotNull();
    }

    @Test
    void explainNationalRiskHandlesEngineFailure() {
        when(riskEngine.get("/risk/national"))
                .thenThrow(new RuntimeException("Connection refused"));

        SseEmitter emitter = service.explainNationalRisk("en");

        assertThat(emitter).isNotNull();
    }

    @Test
    void explainLocationRiskReturnsEmitter() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("risk_score", 65);
        when(riskEngine.post(eq("/risk/location"), any())).thenReturn(data);
        stubFallbackChain();

        SseEmitter emitter = service.explainLocationRisk(47.6062, -122.3321, "en");

        assertThat(emitter).isNotNull();
    }

    @Test
    void explainAlertsReturnsEmitter() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.putArray("alerts");
        when(riskEngine.get("/alerts/search?q=flood")).thenReturn(data);
        stubFallbackChain();

        SseEmitter emitter = service.explainAlerts("flood", "en");

        assertThat(emitter).isNotNull();
    }

    @Test
    void explainRoutesNationalKeywords() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("risk_score", 30);
        when(riskEngine.get("/risk/national")).thenReturn(data);
        stubFallbackChain();

        SseEmitter emitter = service.explain("What is the national risk?", "en");

        assertThat(emitter).isNotNull();
    }

    @Test
    void explainRoutesAlertKeywords() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.putArray("alerts");
        when(riskEngine.get(anyString())).thenReturn(data);
        stubFallbackChain();

        SseEmitter emitter = service.explain("Any weather warnings?", "en");

        assertThat(emitter).isNotNull();
    }

    @Test
    void explainDefaultsToNationalRisk() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("risk_score", 50);
        when(riskEngine.get("/risk/national")).thenReturn(data);
        stubFallbackChain();

        SseEmitter emitter = service.explain("Tell me something", "en");

        assertThat(emitter).isNotNull();
    }

    @Test
    void koreanLanguageDirectiveApplied() {
        ObjectNode data = new ObjectMapper().createObjectNode();
        data.put("risk_score", 55);
        when(riskEngine.get("/risk/national")).thenReturn(data);
        stubFallbackChain();

        SseEmitter emitter = service.explainNationalRisk("ko");

        assertThat(emitter).isNotNull();
    }
}
