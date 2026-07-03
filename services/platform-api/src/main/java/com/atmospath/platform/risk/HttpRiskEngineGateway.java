package com.atmospath.platform.risk;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "atmospath.risk-engine-mode", havingValue = "http", matchIfMissing = true)
public class HttpRiskEngineGateway implements RiskEngineGateway {
    private final RestClient client;

    public HttpRiskEngineGateway(RestClient riskEngineClient) {
        this.client = riskEngineClient;
    }

    @Override
    public JsonNode get(String path) {
        return client.get().uri(path).retrieve().body(JsonNode.class);
    }

    @Override
    public byte[] getBytes(String path) {
        return client.get().uri(path).retrieve().body(byte[].class);
    }

    @Override
    public JsonNode post(String path, JsonNode body) {
        return client.post().uri(path).body(body).retrieve().body(JsonNode.class);
    }
}
