package com.freightscaler.tracking.consumer;

import com.freightscaler.tracking.model.TelemetryRaw;
import com.freightscaler.tracking.model.TrackingEvent;
import com.freightscaler.tracking.repository.TrackingRepository;
import com.freightscaler.tracking.service.TrackingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka consumer that accumulates telemetry events and flushes them
 * to TimescaleDB in batches. Also broadcasts positions via WebSocket.
 */
@Component
public class TelemetryConsumer {

    private static final Logger log = LoggerFactory.getLogger(TelemetryConsumer.class);

    private final ConcurrentLinkedQueue<TrackingEvent> buffer = new ConcurrentLinkedQueue<>();
    private final AtomicLong consumedCount = new AtomicLong();
    private final AtomicLong flushedCount = new AtomicLong();

    private final TrackingRepository repository;
    private final TrackingService trackingService;
    private final int batchSize;

    public TelemetryConsumer(
            TrackingRepository repository,
            TrackingService trackingService,
            @Value("${tracking.batch-size:100}") int batchSize) {
        this.repository = repository;
        this.trackingService = trackingService;
        this.batchSize = batchSize;
    }

    @KafkaListener(topics = "${tracking.topic}", groupId = "tracking-svc")
    public void consume(TelemetryRaw raw) {
        TrackingEvent event = new TrackingEvent(
                raw.timestamp(),
                raw.truckId(),
                raw.corridorId(),
                raw.lat(),
                raw.lon(),
                raw.speedKmh(),
                raw.heading(),
                null // riskScore not present in raw telemetry
        );
        buffer.add(event);
        long count = consumedCount.incrementAndGet();

        if (count % 1000 == 0) {
            log.info("Consumed {} events total, buffer size ~{}", count, buffer.size());
        }

        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    /**
     * Scheduled flush: fires every flush-interval-ms (default 1s)
     * to ensure latency stays bounded even under low throughput.
     */
    @Scheduled(fixedDelayString = "${tracking.flush-interval-ms:1000}")
    public void scheduledFlush() {
        if (!buffer.isEmpty()) {
            flush();
        }
    }

    private synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }

        List<TrackingEvent> batch = new ArrayList<>(batchSize);
        TrackingEvent event;
        while ((event = buffer.poll()) != null && batch.size() < batchSize * 2) {
            batch.add(event);
        }

        if (batch.isEmpty()) {
            return;
        }

        long start = System.nanoTime();
        try {
            repository.batchInsert(batch);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            long total = flushedCount.addAndGet(batch.size());
            log.info("Flushed {} events to DB ({} ms). Total flushed: {}", batch.size(), elapsedMs, total);

            // Broadcast to WebSocket subscribers
            trackingService.broadcastPositions(batch);
        } catch (Exception e) {
            log.error("Failed to flush {} events to DB, re-queuing", batch.size(), e);
            buffer.addAll(batch);
        }
    }

    public long getConsumedCount() {
        return consumedCount.get();
    }

    public long getFlushedCount() {
        return flushedCount.get();
    }
}
