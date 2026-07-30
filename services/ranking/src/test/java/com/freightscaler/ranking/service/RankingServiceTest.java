package com.freightscaler.ranking.service;

import com.freightscaler.ranking.model.CarrierScore;
import com.freightscaler.ranking.model.RankingSnapshot;
import com.freightscaler.ranking.repository.RankingSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RankingServiceTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zSetOps = mock(ZSetOperations.class);

    private final RankingSnapshotRepository snapshotRepository = mock(RankingSnapshotRepository.class);

    private RankingService service;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOps);
        service = new RankingService(redisTemplate, snapshotRepository, 0.99);
    }

    @Test
    void getTopCarriersReadsCorridorKeyDescendingWithRanks() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>(first.toString(), 90.0));
        tuples.add(new DefaultTypedTuple<>(second.toString(), 80.0));
        when(zSetOps.reverseRangeWithScores("ranking:corridor:C-1", 0, 9L)).thenReturn(tuples);

        List<CarrierScore> top = service.getTopCarriers("C-1", 10);

        assertThat(top).hasSize(2);
        assertThat(top.get(0)).isEqualTo(new CarrierScore(first, 90.0, 1));
        assertThat(top.get(1)).isEqualTo(new CarrierScore(second, 80.0, 2));
    }

    @Test
    void getOverallTopReadsOverallKey() {
        when(zSetOps.reverseRangeWithScores("ranking:overall", 0, 4L)).thenReturn(new LinkedHashSet<>());

        List<CarrierScore> top = service.getOverallTop(5);

        assertThat(top).isEmpty();
        verify(zSetOps).reverseRangeWithScores("ranking:overall", 0, 4L);
    }

    @Test
    void incrementScoreBuildsCorridorKey() {
        UUID carrierId = UUID.randomUUID();
        when(zSetOps.incrementScore("ranking:corridor:C-1", carrierId.toString(), 10.0)).thenReturn(42.0);

        double result = service.incrementScore("corridor", "C-1", carrierId, 10.0);

        assertThat(result).isEqualTo(42.0);
    }

    @Test
    void incrementScoreOverallIgnoresCorridorId() {
        UUID carrierId = UUID.randomUUID();
        when(zSetOps.incrementScore("ranking:overall", carrierId.toString(), 5.0)).thenReturn(5.0);

        double result = service.incrementScore("overall", null, carrierId, 5.0);

        assertThat(result).isEqualTo(5.0);
        verify(zSetOps).incrementScore("ranking:overall", carrierId.toString(), 5.0);
    }

    @Test
    void incrementScoreCorridorWithoutCorridorIdRejected() {
        assertThatThrownBy(() -> service.incrementScore("corridor", null, UUID.randomUUID(), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void getCarrierScoresScansCorridorKeys() {
        UUID carrierId = UUID.randomUUID();
        Cursor<String> cursor = cursorOf("ranking:corridor:C-1", "ranking:corridor:C-2");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(zSetOps.score("ranking:corridor:C-1", carrierId.toString())).thenReturn(15.0);
        when(zSetOps.score("ranking:corridor:C-2", carrierId.toString())).thenReturn(null);

        Map<String, Double> scores = service.getCarrierScores(carrierId);

        assertThat(scores).containsExactly(Map.entry("C-1", 15.0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotPersistsOverallAndCorridorRanks() {
        UUID carrierA = UUID.randomUUID();
        UUID carrierB = UUID.randomUUID();

        Cursor<String> cursor = cursorOf("ranking:overall", "ranking:corridor:C-1");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        Set<ZSetOperations.TypedTuple<String>> overall = new LinkedHashSet<>();
        overall.add(new DefaultTypedTuple<>(carrierA.toString(), 50.0));
        overall.add(new DefaultTypedTuple<>(carrierB.toString(), 20.0));
        when(zSetOps.reverseRangeWithScores("ranking:overall", 0, -1)).thenReturn(overall);

        Set<ZSetOperations.TypedTuple<String>> corridor = new LinkedHashSet<>();
        corridor.add(new DefaultTypedTuple<>(carrierA.toString(), 30.0));
        when(zSetOps.reverseRangeWithScores("ranking:corridor:C-1", 0, -1)).thenReturn(corridor);

        int rows = service.snapshot();

        assertThat(rows).isEqualTo(3);
        ArgumentCaptor<List<RankingSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(snapshotRepository).batchInsertSnapshots(captor.capture());
        List<RankingSnapshot> saved = captor.getValue();

        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).category()).isEqualTo("overall");
        assertThat(saved.get(0).corridorId()).isNull();
        assertThat(saved.get(0).carrierId()).isEqualTo(carrierA);
        assertThat(saved.get(0).rank()).isEqualTo(1);
        assertThat(saved.get(1).carrierId()).isEqualTo(carrierB);
        assertThat(saved.get(1).rank()).isEqualTo(2);
        assertThat(saved.get(2).category()).isEqualTo("corridor");
        assertThat(saved.get(2).corridorId()).isEqualTo("C-1");
        assertThat(saved.get(2).rank()).isEqualTo(1);
        // one shared snapshot timestamp per cycle
        assertThat(saved.get(0).snapshotTime()).isEqualTo(saved.get(2).snapshotTime());
    }

    @Test
    void decayScoresMultipliesByDecayFactor() {
        UUID carrierA = UUID.randomUUID();
        Cursor<String> cursor = cursorOf("ranking:overall");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        tuples.add(new DefaultTypedTuple<>(carrierA.toString(), 100.0));
        when(zSetOps.rangeWithScores("ranking:overall", 0, -1)).thenReturn(tuples);

        service.decayScores();

        ArgumentCaptor<Double> newScore = ArgumentCaptor.forClass(Double.class);
        verify(zSetOps).add(eq("ranking:overall"), eq(carrierA.toString()), newScore.capture());
        assertThat(newScore.getValue()).isCloseTo(99.0, within(1e-9));
    }

    @SuppressWarnings("unchecked")
    private static Cursor<String> cursorOf(String... keys) {
        Cursor<String> cursor = mock(Cursor.class);
        if (keys.length == 0) {
            when(cursor.hasNext()).thenReturn(false);
            return cursor;
        }
        Boolean[] hasNextAnswers = new Boolean[keys.length + 1];
        for (int i = 0; i < keys.length; i++) {
            hasNextAnswers[i] = true;
        }
        hasNextAnswers[keys.length] = false;
        when(cursor.hasNext()).thenReturn(true, java.util.Arrays.copyOfRange(hasNextAnswers, 1, hasNextAnswers.length));
        if (keys.length == 1) {
            when(cursor.next()).thenReturn(keys[0]);
        } else {
            String first = keys[0];
            String[] rest = java.util.Arrays.copyOfRange(keys, 1, keys.length);
            when(cursor.next()).thenReturn(first, rest);
        }
        return cursor;
    }
}
