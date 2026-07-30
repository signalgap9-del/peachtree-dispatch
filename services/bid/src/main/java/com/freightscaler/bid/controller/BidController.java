package com.freightscaler.bid.controller;

import com.freightscaler.bid.model.Bid;
import com.freightscaler.bid.model.BidSubmission;
import com.freightscaler.bid.service.BidService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/bids")
public class BidController {

    private final BidService bidService;

    public BidController(BidService bidService) {
        this.bidService = bidService;
    }

    /**
     * Submit a bid. Returns 202 immediately — the actual DB write is
     * flattened through Kafka for sequential per-load processing.
     */
    @PostMapping
    public ResponseEntity<Map<String, String>> submitBid(@Valid @RequestBody BidSubmission submission) {
        bidService.submitBid(submission);
        return ResponseEntity.accepted()
                .body(Map.of(
                        "status", "ACCEPTED",
                        "message", "Bid queued for processing"
                ));
    }

    /**
     * Accept a bid. Uses optimistic locking — returns 409 on conflict.
     */
    @PostMapping("/{id}/accept")
    public ResponseEntity<?> acceptBid(@PathVariable Long id) {
        Bid accepted = bidService.acceptBid(id);
        return ResponseEntity.ok(accepted);
    }

    /**
     * Withdraw a bid. Only the owning carrier can withdraw a SUBMITTED bid.
     */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdrawBid(
            @PathVariable Long id,
            @RequestParam UUID carrierId
    ) {
        Bid withdrawn = bidService.withdrawBid(id, carrierId);
        return ResponseEntity.ok(withdrawn);
    }

    /**
     * Get all bids for a given load.
     */
    @GetMapping("/load/{loadId}")
    public ResponseEntity<List<Bid>> getBidsForLoad(@PathVariable Long loadId) {
        return ResponseEntity.ok(bidService.getBidsForLoad(loadId));
    }

    /**
     * Get bids for a carrier with keyset pagination.
     */
    @GetMapping("/carrier/{carrierId}")
    public ResponseEntity<List<Bid>> getBidsForCarrier(
            @PathVariable UUID carrierId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ResponseEntity.ok(bidService.getBidsForCarrier(carrierId, cursor, limit));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "bid-service"));
    }

    // --- Exception handlers ---

    @ExceptionHandler(BidService.DuplicateBidException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(BidService.DuplicateBidException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "DUPLICATE_BID", "message", ex.getMessage()));
    }

    @ExceptionHandler(BidService.RateLimitExceededException.class)
    public ResponseEntity<Map<String, String>> handleRateLimit(BidService.RateLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "RATE_LIMITED", "message", ex.getMessage()));
    }

    @ExceptionHandler(BidService.BidNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(BidService.BidNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("error", "NOT_FOUND", "message", ex.getMessage()));
    }

    @ExceptionHandler(BidService.BidConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(BidService.BidConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "CONFLICT", "message", ex.getMessage()));
    }
}
