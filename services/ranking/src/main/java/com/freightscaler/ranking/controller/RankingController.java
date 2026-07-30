package com.freightscaler.ranking.controller;

import com.freightscaler.ranking.model.CarrierScore;
import com.freightscaler.ranking.model.RankingSnapshot;
import com.freightscaler.ranking.repository.RankingSnapshotRepository;
import com.freightscaler.ranking.service.RankingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * REST API for rankings. Live queries (corridor/overall/carrier) are served
 * from Redis; history is served from the PostgreSQL snapshot table.
 */
@RestController
@RequestMapping("/rankings")
public class RankingController {

    private static final int MAX_LIMIT = 100;

    private final RankingService rankingService;
    private final RankingSnapshotRepository snapshotRepository;

    public RankingController(RankingService rankingService, RankingSnapshotRepository snapshotRepository) {
        this.rankingService = rankingService;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * Top carriers for a corridor, real-time from Redis.
     */
    @GetMapping("/corridor/{corridorId}")
    public List<CarrierScore> corridorRankings(
            @PathVariable String corridorId,
            @RequestParam(defaultValue = "10") int limit) {
        return rankingService.getTopCarriers(corridorId, clamp(limit));
    }

    /**
     * Top carriers overall, real-time from Redis.
     */
    @GetMapping("/overall")
    public List<CarrierScore> overallRankings(@RequestParam(defaultValue = "10") int limit) {
        return rankingService.getOverallTop(clamp(limit));
    }

    /**
     * One carrier's scores across all corridors plus its overall score.
     */
    @GetMapping("/carrier/{carrierId}")
    public ResponseEntity<Map<String, Object>> carrierScores(@PathVariable UUID carrierId) {
        Map<String, Double> corridors = rankingService.getCarrierScores(carrierId);
        double overall = Optional.ofNullable(rankingService.getOverallScore(carrierId)).orElse(0.0);
        return ResponseEntity.ok(Map.of(
                "carrierId", carrierId.toString(),
                "overall", overall,
                "corridors", corridors
        ));
    }

    /**
     * Historical snapshots from PostgreSQL. corridorId may be omitted for
     * category=overall history.
     */
    @GetMapping("/history")
    public List<RankingSnapshot> history(
            @RequestParam String category,
            @RequestParam(required = false) String corridorId,
            @RequestParam UUID carrierId,
            @RequestParam(defaultValue = "30") int limit) {
        return snapshotRepository.findHistory(category, corridorId, carrierId, clamp(limit));
    }

    /**
     * Triggers a snapshot immediately (for testing / operations).
     */
    @PostMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> triggerSnapshot() {
        int rows = rankingService.snapshot();
        return ResponseEntity.ok(Map.of("snapshotRows", rows));
    }

    /**
     * Simple health check endpoint.
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "ranking"
        ));
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
