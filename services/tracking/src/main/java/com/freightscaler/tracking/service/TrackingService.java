package com.freightscaler.tracking.service;

import com.freightscaler.tracking.model.TrackingEvent;
import com.freightscaler.tracking.repository.TrackingRepository;
import com.freightscaler.tracking.websocket.TrackingWebSocketHandler;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Service layer coordinating repository queries and WebSocket broadcasts.
 */
@Service
public class TrackingService {

    private final TrackingRepository repository;
    private final TrackingWebSocketHandler webSocketHandler;

    public TrackingService(TrackingRepository repository, TrackingWebSocketHandler webSocketHandler) {
        this.repository = repository;
        this.webSocketHandler = webSocketHandler;
    }

    /**
     * Get truck position history with keyset pagination.
     * Returns the page of events; the caller derives nextCursor from the last element's time.
     */
    public List<TrackingEvent> getTruckHistory(UUID truckId, Long cursor, int limit) {
        return repository.findByTruckId(truckId, cursor, limit);
    }

    /**
     * Get latest position of all active trucks on a corridor.
     */
    public List<TrackingEvent> getActiveTrucks(String corridorId) {
        return repository.findActiveByCorridor(corridorId);
    }

    /**
     * Count active trucks on a corridor.
     */
    public long countActiveTrucks(String corridorId) {
        return repository.countByCorridor(corridorId);
    }

    /**
     * Broadcast a batch of tracking events to matching WebSocket subscribers.
     */
    public void broadcastPositions(List<TrackingEvent> events) {
        webSocketHandler.broadcast(events);
    }
}
