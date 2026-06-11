package com.atmospath.platform.risk;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvocationType;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;

@Component
@ConditionalOnProperty(name = "atmospath.risk-engine-mode", havingValue = "lambda")
public class LambdaRiskEngineGateway implements RiskEngineGateway {
    private final LambdaClient lambda;
    private final ObjectMapper mapper;
    private final String functionName;

    public LambdaRiskEngineGateway(
            LambdaClient lambda,
            ObjectMapper mapper,
            @Value("${atmospath.risk-engine-function-name}") String functionName) {
        this.lambda = lambda;
        this.mapper = mapper;
        this.functionName = functionName;
    }

    @Override
    public JsonNode get(String path) {
        URI uri = URI.create("http://risk-engine" + path);
        return invoke("GET", uri.getPath(), query(uri), null);
    }

    @Override
    public JsonNode post(String path, JsonNode body) {
        return invoke("POST", path, Map.of(), body);
    }

    private JsonNode invoke(String method, String path, Map<String, String> query, JsonNode body) {
        try {
            var event = mapper.createObjectNode();
            event.put("method", method);
            event.put("path", path);
            event.set("query", mapper.valueToTree(query));
            event.set("body", body == null ? mapper.createObjectNode() : body);
            var response = lambda.invoke(
                    InvokeRequest.builder()
                            .functionName(functionName)
                            .invocationType(InvocationType.REQUEST_RESPONSE)
                            .payload(SdkBytes.fromUtf8String(mapper.writeValueAsString(event)))
                            .build());
            if (response.functionError() != null) {
                throw new IllegalStateException("Risk engine Lambda failed: " + response.functionError());
            }
            return mapper.readTree(response.payload().asUtf8String()).path("data");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to invoke risk engine", exception);
        }
    }

    private Map<String, String> query(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) return values;
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(
                    URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
        }
        return values;
    }
}
