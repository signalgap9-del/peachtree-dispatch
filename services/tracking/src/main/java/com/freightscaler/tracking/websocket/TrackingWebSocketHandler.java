package com.freightscaler.tracking.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.freightscaler.tracking.model.TrackingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * WebSocket handler for real-time truck position streaming.
 * Clients connect to /ws/tracking?corridor=I-10 or /ws/tracking?truck=UUID
 * and receive JSON position updates matching their subscription.
 */
@Component
public class TrackingWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TrackingWebSocketHandler.class);

    // corridor subscriptions: corridorId -> set of sessions
    private final ConcurrentHashMap<String, Set<WebSocketSession>> corridorSubscriptions = new ConcurrentHashMap<>();
    // truck subscriptions: truckId -> set of sessions
    private final ConcurrentHashMap<String, Set<WebSocketSession>> truckSubscriptions = new ConcurrentHashMap<>();
    // all active sessions for heartbeat
    private final Set<WebSocketSession> activeSessions = new CopyOnWriteArraySet<>();

    private final ObjectMapper objectMapper;

    public TrackingWebSocketHandler() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        activeSessions.add(session);
        registerFromUri(session);
        log.info("WebSocket connected: {} (total: {})", session.getId(), activeSessions.size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // Allow clients to change subscription via message: {"corridor":"I-10"} or {"truck":"uuid"}
        String payload = message.getPayload();
        try {
            @SuppressWarnings("unchecked")
            Map<String, String> sub = objectMapper.readValue(payload, Map.class);
            if (sub.containsKey("corridor")) {
                corridorSubscriptions.computeIfAbsent(sub.get("corridor"), k -> new CopyOnWriteArraySet<>()).add(session);
                session.sendMessage(new TextMessage("{\"subscribed\":\"corridor:" + sub.get("corridor") + "\"}"));
            } else if (sub.containsKey("truck")) {
                truckSubscriptions.computeIfAbsent(sub.get("truck"), k -> new CopyOnWriteArraySet<>()).add(session);
                session.sendMessage(new TextMessage("{\"subscribed\":\"truck:" + sub.get("truck") + "\"}"));
            }
        } catch (Exception e) {
            session.sendMessage(new TextMessage("{\"error\":\"invalid subscription format\"}"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        activeSessions.remove(session);
        corridorSubscriptions.values().forEach(sessions -> sessions.remove(session));
        truckSubscriptions.values().forEach(sessions -> sessions.remove(session));
        log.info("WebSocket closed: {} (status: {}, remaining: {})", session.getId(), status, activeSessions.size());
    }

    /**
     * Broadcast tracking events to matching subscribers.
     */
    public void broadcast(List<TrackingEvent> events) {
        for (TrackingEvent event : events) {
            try {
                String json = objectMapper.writeValueAsString(event);
                TextMessage msg = new TextMessage(json);

                // Send to corridor subscribers
                if (event.corridorId() != null) {
                    Set<WebSocketSession> sessions = corridorSubscriptions.get(event.corridorId());
                    if (sessions != null) {
                        sendToSessions(sessions, msg);
                    }
                }

                // Send to truck subscribers
                String truckKey = event.truckId().toString();
                Set<WebSocketSession> truckSessions = truckSubscriptions.get(truckKey);
                if (truckSessions != null) {
                    sendToSessions(truckSessions, msg);
                }
            } catch (Exception e) {
                log.warn("Failed to serialize/broadcast event for truck {}", event.truckId(), e);
            }
        }
    }

    /**
     * Heartbeat: send ping every 30s to detect dead connections.
     */
    @Scheduled(fixedRate = 30_000)
    public void heartbeat() {
        PingMessage ping = new PingMessage(ByteBuffer.wrap("hb".getBytes()));
        for (WebSocketSession session : activeSessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(ping);
                } catch (IOException e) {
                    log.debug("Heartbeat failed for session {}, removing", session.getId());
                    activeSessions.remove(session);
                }
            } else {
                activeSessions.remove(session);
            }
        }
    }

    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    private void registerFromUri(WebSocketSession session) {
        if (session.getUri() == null) return;
        var params = UriComponentsBuilder.fromUri(session.getUri()).build().getQueryParams();

        String corridor = params.getFirst("corridor");
        if (corridor != null && !corridor.isBlank()) {
            corridorSubscriptions.computeIfAbsent(corridor, k -> new CopyOnWriteArraySet<>()).add(session);
        }

        String truck = params.getFirst("truck");
        if (truck != null && !truck.isBlank()) {
            truckSubscriptions.computeIfAbsent(truck, k -> new CopyOnWriteArraySet<>()).add(session);
        }
    }

    private void sendToSessions(Set<WebSocketSession> sessions, TextMessage msg) {
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(msg);
                    }
                } catch (IOException e) {
                    log.debug("Send failed for session {}, removing", session.getId());
                    sessions.remove(session);
                }
            } else {
                sessions.remove(session);
            }
        }
    }
}
