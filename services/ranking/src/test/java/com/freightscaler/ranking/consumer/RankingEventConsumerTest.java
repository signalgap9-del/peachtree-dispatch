package com.freightscaler.ranking.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.freightscaler.ranking.repository.LoadLookupRepository;
import com.freightscaler.ranking.service.RankingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RankingEventConsumerTest {

    private final RankingService rankingService = mock(RankingService.class);
    private final LoadLookupRepository loadLookup = mock(LoadLookupRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private RankingEventConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new RankingEventConsumer(rankingService, loadLookup, objectMapper);
    }

    @Test
    void bidAcceptedScoresCorridorAndOverall() {
        UUID carrierId = UUID.randomUUID();
        when(loadLookup.findCorridorByLoadId(42L)).thenReturn(Optional.of("C-1"));
        String json = "{\"bidId\":1,\"loadId\":42,\"carrierId\":\"" + carrierId +
                "\",\"type\":\"BID_ACCEPTED\",\"rateCents\":1500,\"traceId\":\"t1\"," +
                "\"occurredAt\":\"2026-07-31T00:00:00Z\"}";

        consumer.onMessage(new ConsumerRecord<>("bid-events", 0, 0L, "42", json));

        verify(rankingService).incrementScore("corridor", "C-1", carrierId, 10.0);
        verify(rankingService).incrementScore("overall", null, carrierId, 10.0);
    }

    @Test
    void bidAcceptedWithoutCorridorStillScoresOverall() {
        UUID carrierId = UUID.randomUUID();
        when(loadLookup.findCorridorByLoadId(7L)).thenReturn(Optional.empty());
        String json = "{\"bidId\":2,\"loadId\":7,\"carrierId\":\"" + carrierId +
                "\",\"type\":\"BID_ACCEPTED\",\"rateCents\":900}";

        consumer.onMessage(new ConsumerRecord<>("bid-events", 0, 1L, "7", json));

        verify(rankingService, never()).incrementScore(eq("corridor"), any(), any(), anyDouble());
        verify(rankingService).incrementScore("overall", null, carrierId, 10.0);
    }

    @Test
    void nonAcceptedBidTypesAreIgnored() {
        UUID carrierId = UUID.randomUUID();
        String json = "{\"bidId\":3,\"loadId\":8,\"carrierId\":\"" + carrierId +
                "\",\"type\":\"SUBMITTED\",\"rateCents\":900}";

        consumer.onMessage(new ConsumerRecord<>("bid-events", 0, 2L, "8", json));

        verifyNoInteractions(rankingService);
    }

    @Test
    void onTimeDeliveryAddsFive() {
        UUID carrierId = UUID.randomUUID();
        String json = "{\"shipmentId\":\"S-1\",\"carrierId\":\"" + carrierId +
                "\",\"corridorId\":\"C-2\",\"type\":\"DELIVERED\",\"onTime\":true}";

        consumer.onMessage(new ConsumerRecord<>("shipment-events", 0, 0L, "S-1", json));

        verify(rankingService).incrementScore("corridor", "C-2", carrierId, 5.0);
        verify(rankingService).incrementScore("overall", null, carrierId, 5.0);
    }

    @Test
    void lateDeliverySubtractsThree() {
        UUID carrierId = UUID.randomUUID();
        String json = "{\"shipmentId\":\"S-2\",\"carrierId\":\"" + carrierId +
                "\",\"corridorId\":\"C-2\",\"type\":\"DELIVERED\",\"onTime\":false}";

        consumer.onMessage(new ConsumerRecord<>("shipment-events", 0, 1L, "S-2", json));

        verify(rankingService).incrementScore("corridor", "C-2", carrierId, -3.0);
        verify(rankingService).incrementScore("overall", null, carrierId, -3.0);
    }

    @Test
    void nonDeliveredShipmentTypesAreIgnored() {
        UUID carrierId = UUID.randomUUID();
        String json = "{\"shipmentId\":\"S-3\",\"carrierId\":\"" + carrierId +
                "\",\"corridorId\":\"C-2\",\"type\":\"PICKED_UP\",\"onTime\":true}";

        consumer.onMessage(new ConsumerRecord<>("shipment-events", 0, 2L, "S-3", json));

        verifyNoInteractions(rankingService);
    }

    @Test
    void malformedPayloadIsLoggedAndSkipped() {
        consumer.onMessage(new ConsumerRecord<>("bid-events", 0, 3L, "k", "{not-json"));

        verifyNoInteractions(rankingService);
    }

    @Test
    void telemetryIsConsumedWithoutScoring() {
        consumer.onMessage(new ConsumerRecord<>("telemetry-raw", 0, 0L, "truck", "{\"truckId\":\"x\"}"));

        verifyNoInteractions(rankingService);
    }
}
