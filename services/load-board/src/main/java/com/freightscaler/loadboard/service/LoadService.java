package com.freightscaler.loadboard.service;

import com.freightscaler.loadboard.model.Load;
import com.freightscaler.loadboard.model.LoadEvent;
import com.freightscaler.loadboard.model.LoadStatus;
import com.freightscaler.loadboard.producer.LoadEventProducer;
import com.freightscaler.loadboard.repository.LoadRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class LoadService {

    private static final Logger log = LoggerFactory.getLogger(LoadService.class);

    private final LoadRepository repository;
    private final LoadEventProducer eventProducer;

    public LoadService(LoadRepository repository, LoadEventProducer eventProducer) {
        this.repository = repository;
        this.eventProducer = eventProducer;
    }

    public Load createLoad(CreateLoadRequest request) {
        Load load = new Load(
                null,
                request.tenantId(),
                request.origin(),
                request.destination(),
                request.cargoType() != null ? request.cargoType() : com.freightscaler.loadboard.model.CargoType.GENERAL,
                request.weightKg(),
                request.pickupStart(),
                request.pickupEnd(),
                request.deliveryDeadline(),
                request.maxRateCents(),
                request.corridorId(),
                request.corridorRisk(),
                LoadStatus.OPEN,
                1,
                null,
                null
        );

        long id = repository.insert(load);
        Load created = repository.findById(id)
                .orElseThrow(() -> new IllegalStateException("Load not found after insert: " + id));

        eventProducer.publish(new LoadEvent(
                created.id(),
                "LOAD_CREATED",
                created.corridorId(),
                created.cargoType().name(),
                created.maxRateCents(),
                created.corridorRisk(),
                UUID.randomUUID().toString(),
                Instant.now()
        ));

        log.info("Created load {} on corridor {}", id, created.corridorId());
        return created;
    }

    public Load getLoad(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Load not found: " + id));
    }

    public LoadPageResponse listLoads(String corridorId, String cargoType, Long cursor, int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), 100);
        // Fetch one extra row to determine hasMore
        List<Load> loads = repository.findOpen(corridorId, cargoType, cursor, effectiveLimit + 1);

        boolean hasMore = loads.size() > effectiveLimit;
        List<Load> page = hasMore ? loads.subList(0, effectiveLimit) : loads;
        Long nextCursor = hasMore ? page.get(page.size() - 1).id() : null;

        return new LoadPageResponse(page, nextCursor, hasMore);
    }

    public Load matchLoad(Long id) {
        Load load = getLoad(id);

        if (load.status() != LoadStatus.OPEN) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Load " + id + " is not OPEN (current: " + load.status() + ")");
        }

        int newVersion = repository.updateStatus(id, LoadStatus.MATCHED, load.version());
        if (newVersion == -1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Optimistic lock conflict on load " + id + "; retry");
        }

        eventProducer.publish(new LoadEvent(
                id,
                "LOAD_MATCHED",
                load.corridorId(),
                load.cargoType().name(),
                load.maxRateCents(),
                load.corridorRisk(),
                UUID.randomUUID().toString(),
                Instant.now()
        ));

        log.info("Matched load {} (version {} -> {})", id, load.version(), newVersion);
        return repository.findById(id).orElse(load);
    }
}
