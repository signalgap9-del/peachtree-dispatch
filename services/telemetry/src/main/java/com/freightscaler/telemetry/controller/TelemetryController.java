package com.freightscaler.telemetry.controller;

import com.freightscaler.telemetry.model.GpsPing;
import com.freightscaler.telemetry.model.TelemetryEvent;
import com.freightscaler.telemetry.producer.TelemetryProducer;
import com.freightscaler.telemetry.service.RateLimitService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/telemetry")
@Validated
public class TelemetryController {

    private static final Logger log = LoggerFactory.getLogger(TelemetryController.class);

    private final TelemetryProducer producer;
    private final RateLimitService rateLimitService;

    public TelemetryController(TelemetryProducer producer, RateLimitService rateLimitService) {
        this.producer = producer;
        this.rateLimitService = rateLimitService;
    }

    @PostMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping(
            @Valid @RequestBody GpsPing ping,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {

        String traceId = resolveTraceId(requestId);

        if (!rateLimitService.isAllowed(ping.truckId())) {
            log.debug("Rate-limited ping from truck {} (trace={})", ping.truckId(), traceId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of(
                            "error", "rate_limited",
                            "message", "Max 1 ping per truck per 10 seconds",
                            "traceId", traceId
                    ));
        }

        TelemetryEvent event = toEvent(ping, traceId);
        producer.send(event);

        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "traceId", traceId
        ));
    }

    @PostMapping("/ping/batch")
    public ResponseEntity<Map<String, Object>> pingBatch(
            @Valid @RequestBody @Size(max = 100) List<GpsPing> pings,
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {

        String traceId = resolveTraceId(requestId);
        int published = 0;

        for (GpsPing ping : pings) {
            if (!rateLimitService.isAllowed(ping.truckId())) {
                log.debug("Rate-limited batch ping from truck {} (trace={})", ping.truckId(), traceId);
                continue;
            }
            TelemetryEvent event = toEvent(ping, traceId);
            producer.send(event);
            published++;
        }

        return ResponseEntity.accepted().body(Map.of(
                "status", "accepted",
                "traceId", traceId,
                "accepted", published,
                "total", pings.size()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "telemetry"
        ));
    }

    private String resolveTraceId(String requestId) {
        return (requestId != null && !requestId.isBlank()) ? requestId : UUID.randomUUID().toString();
    }

    private TelemetryEvent toEvent(GpsPing ping, String traceId) {
        return new TelemetryEvent(
                ping.truckId(),
                ping.lat(),
                ping.lon(),
                ping.speedKmh(),
                ping.heading(),
                ping.corridorId(),
                ping.timestamp(),
                traceId,
                Instant.now()
        );
    }
}
