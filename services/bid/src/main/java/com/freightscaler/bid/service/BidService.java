package com.freightscaler.bid.service;

import com.freightscaler.bid.model.Bid;
import com.freightscaler.bid.model.BidEvent;
import com.freightscaler.bid.model.BidStatus;
import com.freightscaler.bid.model.BidSubmission;
import com.freightscaler.bid.producer.BidEventProducer;
import com.freightscaler.bid.repository.BidRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class BidService {

    private static final Logger log = LoggerFactory.getLogger(BidService.class);

    private final IdempotencyService idempotencyService;
    private final RateLimitService rateLimitService;
    private final BidEventProducer eventProducer;
    private final BidRepository bidRepository;

    public BidService(
            IdempotencyService idempotencyService,
            RateLimitService rateLimitService,
            BidEventProducer eventProducer,
            BidRepository bidRepository
    ) {
        this.idempotencyService = idempotencyService;
        this.rateLimitService = rateLimitService;
        this.eventProducer = eventProducer;
        this.bidRepository = bidRepository;
    }

    /**
     * Write-flattened bid submission:
     * idempotency check → rate limit check → publish to Kafka → return immediately.
     * The actual DB insert happens asynchronously in the consumer.
     *
     * @throws DuplicateBidException if the carrier already bid on this load recently
     * @throws RateLimitExceededException if the carrier exceeded the per-minute limit
     */
    public void submitBid(BidSubmission submission) {
        if (!idempotencyService.tryAcquire(submission.carrierId(), submission.loadId())) {
            throw new DuplicateBidException(
                    "Carrier " + submission.carrierId() + " already submitted a bid for load " + submission.loadId());
        }

        if (!rateLimitService.isAllowed(submission.carrierId())) {
            throw new RateLimitExceededException(
                    "Carrier " + submission.carrierId() + " exceeded the bid rate limit");
        }

        BidEvent event = new BidEvent(
                null, // bidId assigned by consumer on insert
                submission.loadId(),
                submission.carrierId(),
                "SUBMITTED",
                submission.rateCents(),
                UUID.randomUUID().toString(),
                Instant.now()
        );

        eventProducer.publish(event);
        log.info("Bid submission accepted for Kafka processing: carrier={} load={}",
                submission.carrierId(), submission.loadId());
    }

    /**
     * Accept a bid using optimistic locking on the freight_load table.
     * If the load's version changed or it's no longer OPEN, this is a conflict.
     *
     * @throws BidNotFoundException if the bid does not exist
     * @throws BidConflictException if the optimistic lock fails (load already matched or version changed)
     */
    @Transactional
    public Bid acceptBid(Long bidId) {
        Bid bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new BidNotFoundException("Bid not found: " + bidId));

        if (bid.status() != BidStatus.SUBMITTED) {
            throw new BidConflictException("Bid " + bidId + " is not in SUBMITTED status (current: " + bid.status() + ")");
        }

        boolean locked = bidRepository.acceptWithOptimisticLock(bidId);
        if (!locked) {
            throw new BidConflictException(
                    "Optimistic lock conflict: load for bid " + bidId + " is no longer OPEN or version mismatch");
        }

        bidRepository.updateStatus(bidId, BidStatus.ACCEPTED);
        int rejected = bidRepository.rejectOthersOnLoad(bid.loadId(), bidId);
        log.info("Bid {} accepted on load {}, {} competing bids rejected", bidId, bid.loadId(), rejected);

        // Publish acceptance event
        BidEvent acceptEvent = new BidEvent(
                bidId,
                bid.loadId(),
                bid.carrierId(),
                "BID_ACCEPTED",
                bid.rateCents(),
                UUID.randomUUID().toString(),
                Instant.now()
        );
        eventProducer.publish(acceptEvent);

        return new Bid(
                bid.id(), bid.loadId(), bid.carrierId(), bid.rateCents(),
                bid.estimatedHours(), bid.riskAcknowledgment(), BidStatus.ACCEPTED, bid.createdAt()
        );
    }

    /**
     * Withdraw a bid. Only the owning carrier can withdraw, and only if still SUBMITTED.
     *
     * @throws BidNotFoundException if the bid does not exist
     * @throws BidConflictException if the caller is not the owner or the bid is not SUBMITTED
     */
    @Transactional
    public Bid withdrawBid(Long bidId, UUID carrierId) {
        Bid bid = bidRepository.findById(bidId)
                .orElseThrow(() -> new BidNotFoundException("Bid not found: " + bidId));

        if (!bid.carrierId().equals(carrierId)) {
            throw new BidConflictException("Carrier " + carrierId + " does not own bid " + bidId);
        }

        if (bid.status() != BidStatus.SUBMITTED) {
            throw new BidConflictException("Bid " + bidId + " cannot be withdrawn (current status: " + bid.status() + ")");
        }

        bidRepository.updateStatus(bidId, BidStatus.WITHDRAWN);
        log.info("Bid {} withdrawn by carrier {}", bidId, carrierId);

        return new Bid(
                bid.id(), bid.loadId(), bid.carrierId(), bid.rateCents(),
                bid.estimatedHours(), bid.riskAcknowledgment(), BidStatus.WITHDRAWN, bid.createdAt()
        );
    }

    public List<Bid> getBidsForLoad(Long loadId) {
        return bidRepository.findByLoadId(loadId);
    }

    public List<Bid> getBidsForCarrier(UUID carrierId, Long cursor, int limit) {
        return bidRepository.findByCarrierId(carrierId, cursor, limit);
    }

    // --- Exception types ---

    public static class DuplicateBidException extends RuntimeException {
        public DuplicateBidException(String message) { super(message); }
    }

    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String message) { super(message); }
    }

    public static class BidNotFoundException extends RuntimeException {
        public BidNotFoundException(String message) { super(message); }
    }

    public static class BidConflictException extends RuntimeException {
        public BidConflictException(String message) { super(message); }
    }
}
