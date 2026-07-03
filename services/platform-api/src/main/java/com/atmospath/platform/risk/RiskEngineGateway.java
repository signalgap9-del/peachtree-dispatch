package com.atmospath.platform.risk;

import com.fasterxml.jackson.databind.JsonNode;

public interface RiskEngineGateway {
    JsonNode get(String path);

    byte[] getBytes(String path);

    JsonNode post(String path, JsonNode body);
}
