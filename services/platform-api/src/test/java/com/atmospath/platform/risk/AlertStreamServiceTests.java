package com.atmospath.platform.risk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.client.RestClientException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class AlertStreamServiceTests {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RiskEngineGateway riskEngine = mock(RiskEngineGateway.class);
    private final AlertStreamProperties properties = new AlertStreamProperties(true, 30_000, 15_000, 300_000);
    private final AlertStreamService service = new AlertStreamService(riskEngine, properties);

    @Test
    void connectAddsEmitterToActiveClients() {
        SseEmitter emitter = service.connect();

        assertThat(emitter).isNotNull();
        assertThat(service.connectedClientCount()).isEqualTo(1);
    }

    @Test
    void completedEmitterIsRemovedFromActiveClients() {
        SseEmitter emitter = service.connect();
        emitter.complete();

        // Outside a servlet request the completion callback fires through the
        // async lifecycle; the next failed send must drop the dead emitter.
        service.sendHeartbeat();

        assertThat(service.connectedClientCount()).isZero();
    }

    @Test
    void changedSnapshotBroadcastsNationalRiskEvent() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.addEmitter(emitter);
        JsonNode snapshot = snapshot(4, 2, "Tornado Warning", "Flood Warning");
        when(riskEngine.get("/risk/national")).thenReturn(snapshot);

        service.pollAndBroadcast();

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        String text = eventText(captor.getValue());
        assertThat(text).contains("event:" + AlertStreamService.NATIONAL_RISK_EVENT);
        assertThat(text).contains("Tornado Warning");
        assertThat(text).contains("\"active_alerts\":4");
    }

    @Test
    void unchangedSnapshotDoesNotBroadcastAgain() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.addEmitter(emitter);
        JsonNode snapshot = snapshot(3, 1, "Tornado Warning");
        when(riskEngine.get("/risk/national")).thenReturn(snapshot);

        service.pollAndBroadcast();
        service.pollAndBroadcast();

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void changedAlertNamesBroadcastEvenWhenCountsAreUnchanged() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.addEmitter(emitter);
        when(riskEngine.get("/risk/national"))
                .thenReturn(snapshot(2, 1, "Flood Warning"))
                .thenReturn(snapshot(2, 1, "Tornado Warning"));

        service.pollAndBroadcast();
        service.pollAndBroadcast();

        verify(emitter, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void heartbeatSendsHeartbeatEvent() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.addEmitter(emitter);

        service.sendHeartbeat();

        ArgumentCaptor<SseEmitter.SseEventBuilder> captor =
                ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
        verify(emitter, times(1)).send(captor.capture());
        String text = eventText(captor.getValue());
        assertThat(text).contains("event:" + AlertStreamService.HEARTBEAT_EVENT);
        assertThat(text).contains("\"ts\":\"");
    }

    @Test
    void riskEngineFailureSkipsBroadcastAndKeepsClients() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        service.addEmitter(emitter);
        when(riskEngine.get("/risk/national")).thenThrow(new RestClientException("Connection refused"));

        service.pollAndBroadcast();

        verify(emitter, never()).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(service.connectedClientCount()).isEqualTo(1);
    }

    @Test
    void failedSendDropsOnlyTheBrokenEmitter() throws IOException {
        SseEmitter broken = mock(SseEmitter.class);
        SseEmitter healthy = mock(SseEmitter.class);
        doThrow(new IOException("Broken pipe")).when(broken).send(any(SseEmitter.SseEventBuilder.class));
        service.addEmitter(broken);
        service.addEmitter(healthy);
        when(riskEngine.get("/risk/national")).thenReturn(snapshot(1, 0, "Wind Advisory"));

        service.pollAndBroadcast();

        assertThat(service.connectedClientCount()).isEqualTo(1);
        verify(healthy, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    private static JsonNode snapshot(long activeAlerts, long severeAlerts, String... alertEvents) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("generated_at", "2026-07-25T00:00:00Z");
        node.put("active_alerts", activeAlerts);
        node.put("severe_alerts", severeAlerts);
        var alerts = node.putArray("alerts");
        for (String alertEvent : alertEvents) {
            alerts.addObject().put("event", alertEvent);
        }
        return node;
    }

    private static String eventText(SseEmitter.SseEventBuilder builder) {
        StringBuilder text = new StringBuilder();
        for (ResponseBodyEmitter.DataWithMediaType part : builder.build()) {
            text.append(part.getData());
        }
        return text.toString();
    }
}
