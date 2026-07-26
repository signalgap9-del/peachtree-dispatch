package com.atmospath.platform.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Tests for {@link HttpRiskEngineGateway} using {@link MockRestServiceServer}.
 */
class HttpRiskEngineGatewayTests {

    private HttpRiskEngineGateway gateway;
    private MockRestServiceServer mockServer;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        gateway = new HttpRiskEngineGateway(builder.build());
    }

    @Test
    void getSendsGetRequestAndParsesJson() {
        mockServer.expect(requestTo("/api/risk/national"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"score\":42}", MediaType.APPLICATION_JSON));

        JsonNode result = gateway.get("/api/risk/national");

        assertThat(result.path("score").asInt()).isEqualTo(42);
        mockServer.verify();
    }

    @Test
    void getBytesReturnsBinaryContent() {
        byte[] data = {1, 2, 3};
        mockServer.expect(requestTo("/api/export"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(data, MediaType.APPLICATION_OCTET_STREAM));

        byte[] result = gateway.getBytes("/api/export");

        assertThat(result).isEqualTo(data);
        mockServer.verify();
    }

    @Test
    void postSendsBodyAndParsesResponse() throws Exception {
        JsonNode body = mapper.createObjectNode().put("routeId", "r-1");
        mockServer.expect(requestTo("/api/risk/evaluate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"routeId\":\"r-1\"}"))
                .andRespond(withSuccess("{\"risk\":88}", MediaType.APPLICATION_JSON));

        JsonNode result = gateway.post("/api/risk/evaluate", body);

        assertThat(result.path("risk").asInt()).isEqualTo(88);
        mockServer.verify();
    }
}
