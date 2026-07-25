package com.atmospath.platform.llm.security;

import java.time.Duration;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Graceful degradation for LLM features. Tries the LLM call first, falls back
 * to a Redis cache (fail-open: Redis errors are swallowed), and finally renders
 * a structured fallback with raw data.
 */
@Component
@ConditionalOnProperty(name = "atmospath.llm.enabled", havingValue = "true")
public class LlmFallbackChain {
    private static final Logger log = LoggerFactory.getLogger(LlmFallbackChain.class);
    static final String CACHE_PREFIX = "llm:cache:";
    static final Duration CACHE_TTL = Duration.ofMinutes(10);

    private final ObjectProvider<StringRedisTemplate> redisProvider;

    public LlmFallbackChain(ObjectProvider<StringRedisTemplate> redisProvider) {
        this.redisProvider = redisProvider;
    }

    public FallbackResult executeWithFallback(
            Supplier<ChatCompletion> llmCall,
            String cacheKey,
            StructuredFallback structuredFallback) {
        var startNanos = System.nanoTime();

        // Tier 1: LLM call
        try {
            var completion = llmCall.get();
            cacheResponse(cacheKey, completion.content());
            log.debug("LLM response served from LLM tier, key={}", cacheKey);
            return new FallbackResult(completion.content(), FallbackResult.Source.LLM, elapsedMs(startNanos));
        } catch (Exception e) {
            log.warn("LLM call failed, key={}: {}", cacheKey, e.getMessage());
        }

        // Tier 2: Redis cache (fail-open)
        try {
            var redis = redisProvider.getIfAvailable();
            if (redis != null) {
                var cached = redis.opsForValue().get(CACHE_PREFIX + cacheKey);
                if (cached != null) {
                    log.info("LLM response served from cache tier, key={}", cacheKey);
                    return new FallbackResult(cached, FallbackResult.Source.CACHE, elapsedMs(startNanos));
                }
            }
        } catch (Exception e) {
            log.warn("Redis cache read failed, key={}: {}", cacheKey, e.getMessage());
        }

        // Tier 3: Structured fallback (raw data without LLM explanation)
        log.info("LLM response served from structured fallback tier, key={}", cacheKey);
        return new FallbackResult(structuredFallback.render(), FallbackResult.Source.STRUCTURED, elapsedMs(startNanos));
    }

    public void cacheResponse(String cacheKey, String response) {
        try {
            var redis = redisProvider.getIfAvailable();
            if (redis != null) {
                redis.opsForValue().set(CACHE_PREFIX + cacheKey, response, CACHE_TTL);
            }
        } catch (Exception e) {
            log.warn("Redis cache write failed, key={}: {}", cacheKey, e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    public record FallbackResult(String content, Source source, long latencyMs) {
        public enum Source {LLM, CACHE, STRUCTURED}
    }
}
