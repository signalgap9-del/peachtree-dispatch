package com.atmospath.platform.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Base64;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

/**
 * Tests for {@link LambdaRiskEngineGateway} using a mocked AWS
 * {@link LambdaClient}. Verifies invocation parameters, response
 * parsing, error handling, and query-string extraction.
 */
class LambdaRiskEngineGatewayTests {

    private static final String FUNCTION_NAME = "risk-engine-fn";

    private LambdaClient lambdaClient;
    private ObjectMapper mapper;
    private LambdaRiskEngineGateway gateway;

    @BeforeEach
    void setUp() {
        lambdaClient = mock(LambdaClient.class);
        mapper = new ObjectMapper();
        gateway = new LambdaRiskEngineGateway(lambdaClient, mapper, FUNCTION_NAME);
    }

    private InvokeResponse successResponse(String json) {
        return InvokeResponse.builder()
                .statusCode(200)
                .payload(SdkBytes.fromUtf8String(json))
                .build();
    }

    @Test
    void getSendsCorrectInvocationAndParsesData() throws Exception {
        String responseJson = "{\"data\":{\"score\":72,\"level\":\"HIGH\"}}";
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(successResponse(responseJson));

        JsonNode result = gateway.get("/api/risk/123");

        assertThat(result.path("score").asInt()).isEqualTo(72);
        assertThat(result.path("level").asText()).isEqualTo("HIGH");

        ArgumentCaptor<InvokeRequest> captor = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(lambdaClient).invoke(captor.capture());
        InvokeRequest req = captor.getValue();
        assertThat(req.functionName()).isEqualTo(FUNCTION_NAME);
        assertThat(req.invocationTypeAsString()).isEqualTo("RequestResponse");

        // Verify the event payload structure
        JsonNode event = mapper.readTree(req.payload().asUtf8String());
        assertThat(event.path("method").asText()).isEqualTo("GET");
        assertThat(event.path("path").asText()).isEqualTo("/api/risk/123");
    }

    @Test
    void getExtractsQueryParameters() throws Exception {
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(successResponse("{\"data\":{}}"));

        gateway.get("/api/risk?routeId=abc&limit=10");

        ArgumentCaptor<InvokeRequest> captor = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(lambdaClient).invoke(captor.capture());
        JsonNode event = mapper.readTree(captor.getValue().payload().asUtf8String());
        JsonNode query = event.path("query");
        assertThat(query.path("routeId").asText()).isEqualTo("abc");
        assertThat(query.path("limit").asText()).isEqualTo("10");
    }

    @Test
    void getDecodesUrlEncodedQueryParameters() throws Exception {
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(successResponse("{\"data\":{}}"));

        gateway.get("/api/search?q=black+ice&tag=a%26b");

        ArgumentCaptor<InvokeRequest> captor = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(lambdaClient).invoke(captor.capture());
        JsonNode event = mapper.readTree(captor.getValue().payload().asUtf8String());
        assertThat(event.path("query").path("q").asText()).isEqualTo("black ice");
        assertThat(event.path("query").path("tag").asText()).isEqualTo("a&b");
    }

    @Test
    void postSendsBodyInPayload() throws Exception {
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(successResponse("{\"data\":{\"id\":\"new-1\"}}"));

        JsonNode body = mapper.createObjectNode().put("routeId", "r-1");
        JsonNode result = gateway.post("/api/risk", body);

        assertThat(result.path("id").asText()).isEqualTo("new-1");

        ArgumentCaptor<InvokeRequest> captor = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(lambdaClient).invoke(captor.capture());
        JsonNode event = mapper.readTree(captor.getValue().payload().asUtf8String());
        assertThat(event.path("method").asText()).isEqualTo("POST");
        assertThat(event.path("path").asText()).isEqualTo("/api/risk");
        assertThat(event.path("body").path("routeId").asText()).isEqualTo("r-1");
    }

    @Test
    void getBytesDecodesBase64Body() {
        byte[] binary = {0x01, 0x02, 0x03, (byte) 0xFF};
        String encoded = Base64.getEncoder().encodeToString(binary);
        String responseJson = "{\"body_base64\":\"%s\"}".formatted(encoded);
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(successResponse(responseJson));

        byte[] result = gateway.getBytes("/api/export");

        assertThat(result).isEqualTo(binary);
    }

    @Test
    void getBytesThrowsWhenNoBinaryBody() {
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(successResponse("{\"body_base64\":\"\"}"));

        assertThatThrownBy(() -> gateway.getBytes("/api/export"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no binary body");
    }

    @Test
    void lambdaFunctionErrorThrowsIllegalState() {
        InvokeResponse errorResponse = InvokeResponse.builder()
                .statusCode(200)
                .functionError("Unhandled")
                .payload(SdkBytes.fromUtf8String("{}"))
                .build();
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(errorResponse);

        // The inner "Risk engine Lambda failed" exception is wrapped by the
        // outer catch in invokeRaw, so we check the cause chain.
        assertThatThrownBy(() -> gateway.get("/api/risk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to invoke risk engine")
                .hasCauseInstanceOf(IllegalStateException.class)
                .rootCause()
                .hasMessageContaining("Risk engine Lambda failed");
    }

    @Test
    void lambdaClientExceptionWrapsInIllegalState() {
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenThrow(new RuntimeException("connection timeout"));

        assertThatThrownBy(() -> gateway.get("/api/risk"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unable to invoke risk engine")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void getWithNoQueryStringSendsEmptyQuery() throws Exception {
        when(lambdaClient.invoke(any(InvokeRequest.class)))
                .thenReturn(successResponse("{\"data\":{}}"));

        gateway.get("/api/health");

        ArgumentCaptor<InvokeRequest> captor = ArgumentCaptor.forClass(InvokeRequest.class);
        verify(lambdaClient).invoke(captor.capture());
        JsonNode event = mapper.readTree(captor.getValue().payload().asUtf8String());
        assertThat(event.path("query").size()).isEqualTo(0);
    }
}
