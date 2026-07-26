package com.atmospath.platform.llm.proactive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.atmospath.platform.llm.LiteLlmClient;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.llm.rag.RagResult;
import com.atmospath.platform.llm.rag.RagService;
import com.atmospath.platform.llm.security.LlmFallbackChain;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.atmospath.platform.saas.entity.SavedRouteEntity;
import com.atmospath.platform.saas.entity.TenantMember;
import com.atmospath.platform.saas.repository.SavedRouteRepository;
import com.atmospath.platform.saas.repository.TenantMemberRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

class ProactiveRiskAnalyzerTests {

    private SavedRouteRepository routeRepo;
    private TenantMemberRepository memberRepo;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private RagService ragService;
    private LlmFallbackChain fallbackChain;
    private LiteLlmClient llmClient;
    private RiskEngineGateway riskEngine;
    private ProactiveRiskAnalyzer analyzer;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private static final UUID MEMBER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TENANT_ID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        routeRepo = mock(SavedRouteRepository.class);
        memberRepo = mock(TenantMemberRepository.class);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        ragService = mock(RagService.class);
        fallbackChain = mock(LlmFallbackChain.class);
        llmClient = mock(LiteLlmClient.class);
        riskEngine = mock(RiskEngineGateway.class);

        ObjectProvider<SavedRouteRepository> routeProvider = mock(ObjectProvider.class);
        ObjectProvider<TenantMemberRepository> memberProvider = mock(ObjectProvider.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);

        when(routeProvider.getIfAvailable()).thenReturn(routeRepo);
        when(memberProvider.getIfAvailable()).thenReturn(memberRepo);
        when(redisProvider.getIfAvailable()).thenReturn(redis);
        when(redis.opsForValue()).thenReturn(valueOps);

        PromptTemplateService templates = new PromptTemplateService();
        ProactiveProperties props = new ProactiveProperties(true, 300_000, 60, 10);

        analyzer = new ProactiveRiskAnalyzer(
                routeProvider, memberProvider, redisProvider,
                ragService, fallbackChain, llmClient, templates,
                riskEngine, props, objectMapper);
    }

    private SavedRouteEntity monitoredRoute(String name, int threshold) {
        SavedRouteEntity route = new SavedRouteEntity(MEMBER_ID, null, name);
        ReflectionTestUtils.setField(route, "id", UUID.randomUUID());
        route.setOriginName("Seattle");
        route.setDestinationName("Portland");
        route.setRiskThreshold(threshold);
        route.setMonitorEnabled(true);
        return route;
    }

    private JsonNode alertSnapshot(String event, String severity) {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode alerts = root.putArray("alerts");
        ObjectNode alert = alerts.addObject();
        alert.put("event", event);
        alert.put("severity", severity);
        root.put("active_alerts", 1);
        return root;
    }

    private void stubTenantResolution() {
        TenantMember member = mock(TenantMember.class);
        when(member.getTenantId()).thenReturn(TENANT_ID);
        when(memberRepo.findById(MEMBER_ID)).thenReturn(Optional.of(member));
    }

    private void stubRiskEngine(int riskScore) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("risk_score", riskScore);
        when(riskEngine.post(eq("/directions"), any(JsonNode.class))).thenReturn(response);
    }

    private void stubFallbackChain() {
        when(fallbackChain.executeWithFallback(any(), anyString(), any()))
                .thenAnswer(inv -> {
                    var llmCall = inv.getArgument(0, java.util.function.Supplier.class);
                    try {
                        var result = llmCall.get();
                        return new LlmFallbackChain.FallbackResult(
                                ((com.atmospath.platform.llm.security.ChatCompletion) result).content(),
                                LlmFallbackChain.FallbackResult.Source.LLM, 10);
                    } catch (Exception e) {
                        var fallback = inv.getArgument(2,
                                com.atmospath.platform.llm.security.StructuredFallback.class);
                        return new LlmFallbackChain.FallbackResult(
                                fallback.render(),
                                LlmFallbackChain.FallbackResult.Source.STRUCTURED, 5);
                    }
                });
    }

    @Test
    void alertChangeAffectingMonitoredRouteGeneratesSuggestion() {
        SavedRouteEntity route = monitoredRoute("Seattle to Portland", 55);
        when(routeRepo.findByMonitorEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(route));
        stubTenantResolution();
        stubRiskEngine(78);
        when(ragService.augment(anyString(), any())).thenReturn(RagResult.empty(5));
        when(llmClient.complete(any())).thenReturn("Risk is high. Consider I-84 east.");
        stubFallbackChain();
        when(redis.hasKey(anyString())).thenReturn(false);

        List<RiskSuggestion> suggestions = analyzer.analyzeAlertChange(
                alertSnapshot("Flood Warning", "Severe"));

        assertThat(suggestions).hasSize(1);
        RiskSuggestion s = suggestions.get(0);
        assertThat(s.routeName()).isEqualTo("Seattle to Portland");
        assertThat(s.currentRisk()).isEqualTo(78);
        assertThat(s.severity()).isEqualTo("HIGH");
        assertThat(s.tenantId()).isEqualTo(TENANT_ID);
        assertThat(s.acknowledged()).isFalse();
    }

    @Test
    void alertChangeNotAffectingAnyRouteReturnsEmpty() {
        when(routeRepo.findByMonitorEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of());

        List<RiskSuggestion> suggestions = analyzer.analyzeAlertChange(
                alertSnapshot("Wind Advisory", "Moderate"));

        assertThat(suggestions).isEmpty();
    }

    @Test
    void riskBelowThresholdDoesNotGenerateSuggestion() {
        SavedRouteEntity route = monitoredRoute("Seattle to Portland", 55);
        when(routeRepo.findByMonitorEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(route));
        stubRiskEngine(30);
        when(redis.hasKey(anyString())).thenReturn(false);

        List<RiskSuggestion> suggestions = analyzer.analyzeAlertChange(
                alertSnapshot("Light Rain", "Minor"));

        assertThat(suggestions).isEmpty();
    }

    @Test
    void duplicateWithinDedupWindowIsSuppressed() {
        SavedRouteEntity route = monitoredRoute("Seattle to Portland", 55);
        when(routeRepo.findByMonitorEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(route));
        when(redis.hasKey(anyString())).thenReturn(true);

        List<RiskSuggestion> suggestions = analyzer.analyzeAlertChange(
                alertSnapshot("Flood Warning", "Severe"));

        assertThat(suggestions).isEmpty();
        verify(riskEngine, never()).post(anyString(), any(JsonNode.class));
    }

    @Test
    void ragContextIsRetrievedForSuggestion() {
        SavedRouteEntity route = monitoredRoute("Seattle to Portland", 55);
        when(routeRepo.findByMonitorEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(route));
        stubTenantResolution();
        stubRiskEngine(80);

        RagResult ragResult = new RagResult(
                "[Past observation 1] 2026-01-15, I-5: risk 82/100, flood",
                List.of(), 12, 1);
        when(ragService.augment(anyString(), any())).thenReturn(ragResult);
        when(llmClient.complete(any())).thenReturn("Historical data shows similar flooding.");
        stubFallbackChain();
        when(redis.hasKey(anyString())).thenReturn(false);

        List<RiskSuggestion> suggestions = analyzer.analyzeAlertChange(
                alertSnapshot("Flood Warning", "Severe"));

        assertThat(suggestions).hasSize(1);
        verify(ragService).augment(anyString(), any());
    }

    @Test
    void llmFailureProducesStructuredFallback() {
        SavedRouteEntity route = monitoredRoute("Seattle to Portland", 55);
        when(routeRepo.findByMonitorEnabledTrueAndDeletedAtIsNull()).thenReturn(List.of(route));
        stubTenantResolution();
        stubRiskEngine(85);
        when(ragService.augment(anyString(), any())).thenReturn(RagResult.empty(5));

        // Fallback chain returns structured fallback when LLM fails
        when(fallbackChain.executeWithFallback(any(), anyString(), any()))
                .thenAnswer(inv -> {
                    var fallback = inv.getArgument(2,
                            com.atmospath.platform.llm.security.StructuredFallback.class);
                    return new LlmFallbackChain.FallbackResult(
                            fallback.render(),
                            LlmFallbackChain.FallbackResult.Source.STRUCTURED, 5);
                });
        when(redis.hasKey(anyString())).thenReturn(false);

        List<RiskSuggestion> suggestions = analyzer.analyzeAlertChange(
                alertSnapshot("Blizzard Warning", "Extreme"));

        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).suggestionText()).contains("Risk alert for saved route");
        assertThat(suggestions.get(0).suggestionText()).contains("85/100");
    }

    @Test
    void alertSummaryExtractsEventNames() {
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode alerts = root.putArray("alerts");
        alerts.addObject().put("event", "Flood Warning").put("severity", "Severe");
        alerts.addObject().put("event", "Wind Advisory").put("severity", "Moderate");

        String summary = analyzer.extractAlertSummary(root);

        assertThat(summary).contains("Flood Warning");
        assertThat(summary).contains("Wind Advisory");
    }

    @Test
    void emptySnapshotReturnsNoAlertSummary() {
        ObjectNode root = objectMapper.createObjectNode();

        String summary = analyzer.extractAlertSummary(root);

        assertThat(summary).isEqualTo("No active alerts");
    }
}
