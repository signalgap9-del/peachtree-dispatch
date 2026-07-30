package com.freightscaler.ranking.service;

import com.freightscaler.ranking.model.CarrierScore;
import com.freightscaler.ranking.model.RankingSnapshot;
import com.freightscaler.ranking.repository.RankingSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real-time carrier rankings backed by Redis Sorted Sets.
 *
 * <p>Key layout:
 * <ul>
 *   <li>{@code ranking:overall} — global carrier standing</li>
 *   <li>{@code ranking:corridor:{corridorId}} — per-corridor standing</li>
 * </ul>
 * Members are carrier UUID strings; scores are incremented in place
 * (ZINCRBY) so updates are sub-millisecond. Snapshots are persisted to
 * PostgreSQL every {@code ranking.snapshot-interval-ms} for historical
 * queries, and a nightly job decays all scores so recent performance
 * weighs more than old results.
 */
@Service
public class RankingService {

    private static final Logger log = LoggerFactory.getLogger(RankingService.class);

    static final String OVERALL_KEY = "ranking:overall";
    static final String CORRIDOR_KEY_PREFIX = "ranking:corridor:";
    static final String ALL_KEYS_PATTERN = "ranking:*";
    private static final int SCAN_COUNT_HINT = 200;

    private final StringRedisTemplate redisTemplate;
    private final ZSetOperations<String, String> zSetOps;
    private final RankingSnapshotRepository snapshotRepository;
    private final double decayFactor;

    public RankingService(
            StringRedisTemplate redisTemplate,
            RankingSnapshotRepository snapshotRepository,
            @Value("${ranking.decay-factor:0.99}") double decayFactor) {
        this.redisTemplate = redisTemplate;
        this.zSetOps = redisTemplate.opsForZSet();
        this.snapshotRepository = snapshotRepository;
        this.decayFactor = decayFactor;
    }

    /**
     * Top carriers for a corridor, highest score first (ZREVRANGE ... WITHSCORES).
     */
    public List<CarrierScore> getTopCarriers(String corridorId, int limit) {
        return topEntries(CORRIDOR_KEY_PREFIX + corridorId, limit);
    }

    /**
     * Top carriers overall, highest score first.
     */
    public List<CarrierScore> getOverallTop(int limit) {
        return topEntries(OVERALL_KEY, limit);
    }

    /**
     * The carrier's score on every corridor it currently appears in.
     * Scans corridor keys (SCAN, never KEYS) and reads one ZSCORE each.
     */
    public Map<String, Double> getCarrierScores(UUID carrierId) {
        Map<String, Double> scores = new LinkedHashMap<>();
        String member = carrierId.toString();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(CORRIDOR_KEY_PREFIX + "*").count(SCAN_COUNT_HINT).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Double score = zSetOps.score(key, member);
                if (score != null) {
                    scores.put(key.substring(CORRIDOR_KEY_PREFIX.length()), score);
                }
            }
        }
        return scores;
    }

    /**
     * The carrier's overall score, or null if it has never been ranked.
     */
    public Double getOverallScore(UUID carrierId) {
        return zSetOps.score(OVERALL_KEY, carrierId.toString());
    }

    /**
     * Atomic score update (ZINCRBY). category is "overall" or "corridor";
     * corridorId is required for the corridor category. Returns the new score.
     */
    public double incrementScore(String category, String corridorId, UUID carrierId, double delta) {
        String key = keyFor(category, corridorId);
        Double updated = zSetOps.incrementScore(key, carrierId.toString(), delta);
        return updated != null ? updated : delta;
    }

    /**
     * Persists the full current ranking (overall + every corridor) to
     * ranking_snapshot. Runs on a fixed rate; also invoked manually via
     * POST /rankings/snapshot. Returns the number of rows persisted.
     */
    @Scheduled(fixedRateString = "${ranking.snapshot-interval-ms:60000}")
    public int snapshot() {
        Instant snapshotTime = Instant.now();
        List<RankingSnapshot> batch = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(ALL_KEYS_PATTERN).count(SCAN_COUNT_HINT).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                String[] parsed = parseKey(key);
                if (parsed == null) {
                    log.debug("Skipping unrecognized ranking key {}", key);
                    continue;
                }
                Set<ZSetOperations.TypedTuple<String>> tuples = zSetOps.reverseRangeWithScores(key, 0, -1);
                if (tuples == null) {
                    continue;
                }
                int rank = 1;
                for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                    if (tuple.getValue() == null || tuple.getScore() == null) {
                        continue;
                    }
                    batch.add(new RankingSnapshot(
                            snapshotTime,
                            parsed[0],
                            parsed[1],
                            UUID.fromString(tuple.getValue()),
                            tuple.getScore(),
                            rank++));
                }
            }
        }
        if (!batch.isEmpty()) {
            snapshotRepository.batchInsertSnapshots(batch);
            log.info("Persisted ranking snapshot: {} rows", batch.size());
        } else {
            log.debug("Ranking snapshot skipped: no ranked members in Redis");
        }
        return batch.size();
    }

    /**
     * Nightly multiplicative decay: every score becomes score * decayFactor.
     * Keeps the leaderboard fresh so recent performance matters more than
     * results from months ago.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void decayScores() {
        long updated = 0;
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(ALL_KEYS_PATTERN).count(SCAN_COUNT_HINT).build())) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Set<ZSetOperations.TypedTuple<String>> tuples = zSetOps.rangeWithScores(key, 0, -1);
                if (tuples == null) {
                    continue;
                }
                for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                    if (tuple.getValue() == null || tuple.getScore() == null) {
                        continue;
                    }
                    zSetOps.add(key, tuple.getValue(), tuple.getScore() * decayFactor);
                    updated++;
                }
            }
        }
        log.info("Applied decay factor {} to {} ranked members", decayFactor, updated);
    }

    private List<CarrierScore> topEntries(String key, int limit) {
        int effectiveLimit = Math.max(limit, 1);
        Set<ZSetOperations.TypedTuple<String>> tuples =
                zSetOps.reverseRangeWithScores(key, 0, effectiveLimit - 1L);
        if (tuples == null || tuples.isEmpty()) {
            return List.of();
        }
        List<CarrierScore> entries = new ArrayList<>(tuples.size());
        long rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            if (tuple.getValue() == null) {
                continue;
            }
            double score = tuple.getScore() != null ? tuple.getScore() : 0.0;
            entries.add(new CarrierScore(UUID.fromString(tuple.getValue()), score, rank++));
        }
        return entries;
    }

    private String keyFor(String category, String corridorId) {
        if ("overall".equals(category)) {
            return OVERALL_KEY;
        }
        if (corridorId == null || corridorId.isBlank()) {
            throw new IllegalArgumentException("corridorId is required for category '" + category + "'");
        }
        return CORRIDOR_KEY_PREFIX + corridorId;
    }

    private static String[] parseKey(String key) {
        if (OVERALL_KEY.equals(key)) {
            return new String[] {"overall", null};
        }
        if (key.startsWith(CORRIDOR_KEY_PREFIX)) {
            return new String[] {"corridor", key.substring(CORRIDOR_KEY_PREFIX.length())};
        }
        return null;
    }
}
