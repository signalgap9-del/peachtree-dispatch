package com.atmospath.platform.llm.nl2opt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.atmospath.platform.llm.LlmClient;
import com.atmospath.platform.llm.Message;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.StopConstraint;
import com.atmospath.platform.llm.nl2opt.VrpConstraintSchema.VrpConstraints;
import com.atmospath.platform.llm.prompt.PromptTemplateService;
import com.atmospath.platform.risk.RiskEngineGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class Nl2OptExtractionServiceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LlmClient llmClient = mock(LlmClient.class);
    private final RiskEngineGateway riskEngine = mock(RiskEngineGateway.class);
    private final PromptTemplateService templates = new PromptTemplateService();
    private final VrpConstraintValidator validator = new VrpConstraintValidator();

    private Nl2OptExtractionService service;

    @BeforeEach
    void setUp() {
        service = new Nl2OptExtractionService(llmClient, templates, validator, riskEngine);
    }

    @Test
    void validExtractionOnFirstAttempt() {
        when(llmClient.complete(anyList())).thenReturn(validJson("Denver", "Boulder"));
        when(riskEngine.get(anyString())).thenAnswer(inv -> geoResult(39.7392, -104.9903, "Denver, CO"));

        ExtractionResult result = service.extract("Drive from Denver to Boulder", ConversationContext.empty());

        assertThat(result.constraints().stops()).hasSize(2);
        assertThat(result.constraints().stops().get(0).name()).isEqualTo("Denver");
        assertThat(result.geocodedStops()).containsKey("Denver");
        assertThat(result.geocodedStops().get("Denver").latitude()).isEqualTo(39.7392);
        assertThat(result.repairAttempts()).isZero();
        assertThat(result.unresolvedPlaces()).isEmpty();
        assertThat(result.llmUsage().totalTokens()).isPositive();
    }

    @Test
    void invalidJsonRepairsOnSecondAttempt() {
        String invalidJson = """
                { "stops": [{ "name": "", "type": "origin" }] }
                """;
        when(llmClient.complete(anyList()))
                .thenReturn(invalidJson)
                .thenReturn(validJson("Seattle", "Tacoma"));
        when(riskEngine.get(anyString())).thenAnswer(inv -> geoResult(47.6062, -122.3321, "Seattle, WA"));

        ExtractionResult result = service.extract("Seattle to Tacoma", ConversationContext.empty());

        assertThat(result.repairAttempts()).isEqualTo(1);
        assertThat(result.constraints().stops()).hasSize(2);
        verify(llmClient, times(2)).complete(anyList());
    }

    @Test
    void threeFailedAttemptsThrowsExtractionFailedException() {
        String invalidJson = """
                { "stops": [] }
                """;
        when(llmClient.complete(anyList())).thenReturn(invalidJson);

        assertThatThrownBy(() -> service.extract("nowhere to nowhere", ConversationContext.empty()))
                .isInstanceOf(ExtractionFailedException.class)
                .satisfies(ex -> {
                    ExtractionFailedException efe = (ExtractionFailedException) ex;
                    assertThat(efe.attemptsMade()).isEqualTo(3);
                    assertThat(efe.validationErrors()).contains("stops must not be empty");
                });

        // 1 initial + 3 repairs = 4 calls
        verify(llmClient, times(4)).complete(anyList());
    }

    @Test
    void geocodingFailureMarksStopUnresolved() {
        when(llmClient.complete(anyList())).thenReturn(validJson("Denver", "Boulder"));
        when(riskEngine.get(anyString())).thenThrow(new IllegalStateException("Risk engine down"));

        ExtractionResult result = service.extract("Denver to Boulder", ConversationContext.empty());

        assertThat(result.constraints().stops()).hasSize(2);
        assertThat(result.geocodedStops()).isEmpty();
        assertThat(result.unresolvedPlaces()).containsExactly("Denver", "Boulder");
    }

    @Test
    void contextWithPreviousConstraintsIncludedInPrompt() {
        when(llmClient.complete(anyList())).thenReturn(validJson("Denver", "Boulder"));
        when(riskEngine.get(anyString())).thenReturn(MAPPER.createArrayNode());

        VrpConstraints previous = new VrpConstraints(
                List.of(new StopConstraint("Austin", "origin", null, 0)),
                null, null, null, List.of(), List.of(), null);
        ConversationContext context = new ConversationContext(previous, List.of());

        service.extract("Change destination to Boulder", context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(llmClient).complete(captor.capture());
        String userContent = captor.getValue().get(1).content();
        assertThat(userContent).contains("Current state (previous constraints)");
        assertThat(userContent).contains("Austin");
        assertThat(userContent).contains("Change destination to Boulder");
    }

    @Test
    void koreanInputExtractsCorrectly() {
        String koreanJson = """
                {
                  "stops": [
                    { "name": "시애틀", "type": "origin", "timeWindow": null, "priority": 0 }
                  ],
                  "vehicle": { "type": "truck", "hazmat": false, "capacityKg": null },
                  "departure": { "time": "08:00", "flexibility": "strict" },
                  "arrival": null,
                  "softConstraints": [
                    { "type": "weather_avoidance", "target": "storm", "weight": 0.9, "reason": "폭풍 전에 도착" }
                  ],
                  "hardConstraints": [],
                  "objective": "minimize_time"
                }
                """;
        when(llmClient.complete(anyList())).thenReturn(koreanJson);
        when(riskEngine.get(anyString())).thenAnswer(inv -> geoResult(47.6062, -122.3321, "Seattle, WA"));

        ExtractionResult result = service.extract(
                "시애틀에서 8시 출발, 트럭, 폭풍 전에 도착", ConversationContext.empty());

        assertThat(result.constraints().vehicle().type()).isEqualTo("truck");
        assertThat(result.constraints().departure().time()).isEqualTo("08:00");
        assertThat(result.constraints().softConstraints()).hasSize(1);
        assertThat(result.constraints().softConstraints().get(0).type()).isEqualTo("weather_avoidance");
        assertThat(result.geocodedStops()).containsKey("시애틀");
    }

    @Test
    void minimalInputReturnsMinimalConstraints() {
        String minimalJson = """
                {
                  "stops": [
                    { "name": "home", "type": "origin", "timeWindow": null, "priority": 0 }
                  ],
                  "vehicle": null,
                  "departure": null,
                  "arrival": null,
                  "softConstraints": [],
                  "hardConstraints": [],
                  "objective": null
                }
                """;
        when(llmClient.complete(anyList())).thenReturn(minimalJson);
        when(riskEngine.get(anyString())).thenReturn(MAPPER.createArrayNode());

        ExtractionResult result = service.extract("go somewhere", ConversationContext.empty());

        assertThat(result.constraints().stops()).hasSize(1);
        assertThat(result.constraints().vehicle()).isNull();
        assertThat(result.constraints().departure()).isNull();
        assertThat(result.constraints().objective()).isNull();
    }

    @Test
    void extraFieldsInLlmResponseIgnoredGracefully() {
        String jsonWithExtras = """
                {
                  "stops": [
                    { "name": "Portland", "type": "origin", "timeWindow": null, "priority": 0,
                      "unknownField": "should be ignored" }
                  ],
                  "vehicle": { "type": "car", "hazmat": false, "capacityKg": null, "color": "red" },
                  "departure": null,
                  "arrival": null,
                  "softConstraints": [],
                  "hardConstraints": [],
                  "objective": null,
                  "metadata": { "confidence": 0.95 }
                }
                """;
        when(llmClient.complete(anyList())).thenReturn(jsonWithExtras);
        when(riskEngine.get(anyString())).thenReturn(MAPPER.createArrayNode());

        ExtractionResult result = service.extract("Drive to Portland", ConversationContext.empty());

        assertThat(result.constraints().stops()).hasSize(1);
        assertThat(result.constraints().stops().get(0).name()).isEqualTo("Portland");
        assertThat(result.constraints().vehicle().type()).isEqualTo("car");
        assertThat(result.repairAttempts()).isZero();
    }

    @Test
    void codeFencedJsonIsStrippedBeforeParsing() {
        String fenced = "```json\n" + validJson("Denver", "Boulder") + "\n```";
        when(llmClient.complete(anyList())).thenReturn(fenced);
        when(riskEngine.get(anyString())).thenReturn(MAPPER.createArrayNode());

        ExtractionResult result = service.extract("Denver to Boulder", ConversationContext.empty());

        assertThat(result.constraints().stops()).hasSize(2);
        assertThat(result.repairAttempts()).isZero();
    }

    // --- helpers ---

    private static String validJson(String origin, String destination) {
        return """
                {
                  "stops": [
                    { "name": "%s", "type": "origin", "timeWindow": null, "priority": 0 },
                    { "name": "%s", "type": "destination", "timeWindow": null, "priority": 0 }
                  ],
                  "vehicle": { "type": "car", "hazmat": false, "capacityKg": null },
                  "departure": null,
                  "arrival": null,
                  "softConstraints": [],
                  "hardConstraints": [],
                  "objective": "minimize_time"
                }
                """.formatted(origin, destination);
    }

    private JsonNode geoResult(double lat, double lon, String displayName) {
        ArrayNode array = MAPPER.createArrayNode();
        ObjectNode point = MAPPER.createObjectNode();
        point.put("lat", lat);
        point.put("lon", lon);
        point.put("display_name", displayName);
        point.put("source", "nominatim");
        array.add(point);
        return array;
    }
}
