package com.freightscaler.settlement.controller;

import com.freightscaler.settlement.model.Settlement;
import com.freightscaler.settlement.model.SettlementStatus;
import com.freightscaler.settlement.model.Wallet;
import com.freightscaler.settlement.repository.SettlementRepository;
import com.freightscaler.settlement.service.SettlementSagaOrchestrator;
import com.freightscaler.settlement.service.WalletService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/settlements")
public class SettlementController {

    private static final Logger log = LoggerFactory.getLogger(SettlementController.class);
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final SettlementRepository settlementRepository;
    private final WalletService walletService;
    private final SettlementSagaOrchestrator sagaOrchestrator;

    public SettlementController(
            SettlementRepository settlementRepository,
            WalletService walletService,
            SettlementSagaOrchestrator sagaOrchestrator
    ) {
        this.settlementRepository = settlementRepository;
        this.walletService = walletService;
        this.sagaOrchestrator = sagaOrchestrator;
    }

    /**
     * GET /settlements/{id} — full settlement with saga log.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Settlement> getSettlement(@PathVariable Long id) {
        return settlementRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Settlement not found: " + id));
    }

    /**
     * GET /settlements?carrierId=&shipperId=&status=&cursor=&limit=20
     * Keyset pagination: pass the last settlement id as cursor for the next page.
     */
    @GetMapping
    public ResponseEntity<List<Settlement>> listSettlements(
            @RequestParam(required = false) UUID carrierId,
            @RequestParam(required = false) UUID shipperId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        List<Settlement> results;
        if (carrierId != null) {
            results = settlementRepository.findByCarrier(carrierId, status, cursor, safeLimit);
        } else if (shipperId != null) {
            results = settlementRepository.findByShipper(shipperId, status, cursor, safeLimit);
        } else {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either carrierId or shipperId is required");
        }

        return ResponseEntity.ok(results);
    }

    /**
     * GET /settlements/wallet/{ownerId} — wallet balance lookup.
     */
    @GetMapping("/wallet/{ownerId}")
    public ResponseEntity<Wallet> getWallet(@PathVariable UUID ownerId) {
        return walletService.getBalance(ownerId)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Wallet not found: " + ownerId));
    }

    /**
     * POST /settlements/wallet — create a wallet (for testing/bootstrapping).
     */
    @PostMapping("/wallet")
    public ResponseEntity<Map<String, Object>> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        boolean created = walletService.createWallet(request.ownerId(), request.initialBalanceCents());
        Wallet wallet = walletService.getBalance(request.ownerId()).orElseThrow();

        HttpStatus status = created ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(Map.of(
                "ownerId", wallet.ownerId().toString(),
                "balanceCents", wallet.balanceCents(),
                "created", created
        ));
    }

    /**
     * POST /settlements/{id}/retry — retry a FAILED saga from its last completed step.
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<Map<String, String>> retrySaga(@PathVariable Long id) {
        Settlement s = settlementRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Settlement not found: " + id));

        if (s.status() != SettlementStatus.FAILED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Only FAILED settlements can be retried, current status: " + s.status());
        }

        log.info("Retrying saga for settlement={} from step={}", id, s.currentStep());
        sagaOrchestrator.executeSaga(id);

        Settlement updated = settlementRepository.findById(id).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "settlementId", id.toString(),
                "status", updated.status().name(),
                "currentStep", String.valueOf(updated.currentStep())
        ));
    }

    /**
     * GET /settlements/health — service health check.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "service", "settlement-service",
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }

    public record CreateWalletRequest(
            @NotNull UUID ownerId,
            @PositiveOrZero long initialBalanceCents
    ) {
    }
}
