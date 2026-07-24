package com.atmospath.platform.risk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Fans out national risk snapshots to connected Server-Sent Events clients.
 * The risk engine is polled on a fixed interval; a snapshot is broadcast only
 * when its alert counts or top alert event names change, so clients receive
 * meaningful deltas plus periodic heartbeats to keep connections alive.
 */
@Service
@ConditionalOnProperty(name = "atmospath.alert-stream.enabled", havingValue = "true", matchIfMissing = true)
public class AlertStreamService {

    static final String NATIONAL_RISK_EVENT = "national_risk";
    static final String HEARTBEAT_EVENT = "heartbeat";

    private static final Logger log = LoggerFactory.getLogger(AlertStreamService.class);
    private static final int TOP_ALERT_HASH_LIMIT = 10;

    private final RiskEngineGateway riskEngine;
    private final AlertStreamProperties properties;
    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    private volatile SnapshotSignature lastSignature;

    public AlertStreamService(RiskEngineGateway riskEngine, AlertStreamProperties properties) {
        this.riskEngine = riskEngine;
        this.properties = properties;
    }

    public SseEmitter connect() {
        SseEmitter emitter = new SseEmitter(properties.emitterTimeoutMs());
        addEmitter(emitter);
        return emitter;
    }

    public int connectedClientCount() {
        return emitters.size();
    }

    void addEmitter(SseEmitter emitter) {
        emitters.add(emitter);
        emitter.onCompletion(() -> removeEmitter(emitter, "completed"));
        emitter.onTimeout(() -> removeEmitter(emitter, "timed out"));
        emitter.onError(error -> removeEmitter(emitter, "errored"));
        log.info("Alert stream client connected; {} client(s) connected", emitters.size());
    }

    private void removeEmitter(SseEmitter emitter, String reason) {
        if (emitters.remove(emitter)) {
            log.info("Alert stream client removed ({}); {} client(s) connected", reason, emitters.size());
        }
    }

    @Scheduled(fixedDelayString = "${atmospath.alert-stream.poll-interval-ms:30000}")
    public void pollAndBroadcast() {
        if (emitters.isEmpty()) {
            return;
        }
        JsonNode snapshot;
        try {
            snapshot = riskEngine.get("/risk/national");
        } catch (RuntimeException ex) {
            log.warn("Risk engine unavailable; skipping alert stream poll: {}", ex.toString());
            return;
        }
        if (snapshot == null || snapshot.isMissingNode() || snapshot.isNull()) {
            log.warn("Risk engine returned no national risk payload; skipping alert stream broadcast");
            return;
        }
        SnapshotSignature signature = SnapshotSignature.of(snapshot);
        SnapshotSignature previous = lastSignature;
        lastSignature = signature;
        if (signature.equals(previous)) {
            return;
        }
        log.info("National risk snapshot changed; broadcasting to {} client(s)", emitters.size());
        sendToAll(NATIONAL_RISK_EVENT, snapshot);
    }

    @Scheduled(fixedDelayString = "${atmospath.alert-stream.heartbeat-interval-ms:15000}")
    public void sendHeartbeat() {
        if (emitters.isEmpty()) {
            return;
        }
        sendToAll(HEARTBEAT_EVENT, "{\"ts\":\"" + Instant.now() + "\"}");
    }

    private void sendToAll(String eventName, Object data) {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException ex) {
                removeEmitter(emitter, "send failed");
                try {
                    emitter.complete();
                } catch (RuntimeException ignored) {
                    // The connection is already gone; nothing left to release.
                }
            }
        }
    }

    /**
     * Change-detection fingerprint for a national risk snapshot. The payload's
     * generated_at timestamp changes on every poll, so only the alert counts
     * and a hash of the first alert event names are compared.
     */
    record SnapshotSignature(long activeAlerts, long severeAlerts, String topAlertNamesHash) {

        static SnapshotSignature of(JsonNode snapshot) {
            return new SnapshotSignature(
                    snapshot.path("active_alerts").asLong(0),
                    snapshot.path("severe_alerts").asLong(0),
                    hashTopAlertNames(snapshot));
        }

        private static String hashTopAlertNames(JsonNode snapshot) {
            JsonNode alerts = snapshot.path("alerts");
            if (!alerts.isArray() || alerts.isEmpty()) {
                return "";
            }
            StringBuilder names = new StringBuilder();
            int included = 0;
            for (JsonNode alert : alerts) {
                if (included >= TOP_ALERT_HASH_LIMIT) {
                    break;
                }
                names.append(alert.path("event").asText("")).append('\u0000');
                included++;
            }
            return sha256Hex(names.toString());
        }

        private static String sha256Hex(String value) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 must be available on the JVM", ex);
            }
        }
    }
}
