package com.freightscaler.tracking.controller;

import com.freightscaler.tracking.model.TrackingEvent;
import com.freightscaler.tracking.service.TrackingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for tracking queries.
 */
@RestController
@RequestMapping("/tracking")
public class TrackingController {

    private final TrackingService trackingService;

    public TrackingController(TrackingService trackingService) {
        this.trackingService = trackingService;
    }

    /**
     * Keyset-paginated truck position history.
     * cursor = epoch millis of the last event from the previous page.
     */
    @GetMapping("/truck/{truckId}/history")
    public ResponseEntity<Map<String, Object>> getTruckHistory(
            @PathVariable UUID truckId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit) {

        List<TrackingEvent> events = trackingService.getTruckHistory(truckId, cursor, limit);

        Long nextCursor = null;
        if (!events.isEmpty() && events.size() == limit) {
            nextCursor = events.get(events.size() - 1).time().toEpochMilli();
        }

        return ResponseEntity.ok(Map.of(
                "data", events,
                "nextCursor", nextCursor != null ? nextCursor.toString() : ""
        ));
    }

    /**
     * Active trucks on a corridor (last 5 minutes).
     */
    @GetMapping("/corridor/{corridorId}/active")
    public ResponseEntity<Map<String, Object>> getActiveTrucks(@PathVariable String corridorId) {
        List<TrackingEvent> events = trackingService.getActiveTrucks(corridorId);
        long count = trackingService.countActiveTrucks(corridorId);

        return ResponseEntity.ok(Map.of(
                "corridor", corridorId,
                "activeTrucks", count,
                "positions", events
        ));
    }

    /**
     * Simple health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "tracking"
        ));
    }
}
