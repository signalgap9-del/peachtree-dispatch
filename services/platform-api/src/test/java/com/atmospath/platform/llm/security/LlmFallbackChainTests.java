package com.atmospath.platform.llm.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class LlmFallbackChainTests {

    @Test
    void llmSuccessReturnsLlmResult() {
        var chain = new LlmFallbackChain(redisProviderReturning(null));
        var result = chain.executeWithFallback(
                () -> new ChatCompletion("LLM says hello"),
                "test-key",
                () -> "fallback");

        assertThat(result.content()).isEqualTo("LLM says hello");
        assertThat(result.source()).isEqualTo(LlmFallbackChain.FallbackResult.Source.LLM);
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void llmFailWithCacheHitReturnsCached() {
        var chain = new LlmFallbackChain(redisProviderWithCached("cached response"));
        var result = chain.executeWithFallback(
                () -> { throw new RuntimeException("LLM down"); },
                "test-key",
                () -> "fallback");

        assertThat(result.content()).isEqualTo("cached response");
        assertThat(result.source()).isEqualTo(LlmFallbackChain.FallbackResult.Source.CACHE);
    }

    @Test
    void llmFailWithCacheMissReturnsStructuredFallback() {
        var chain = new LlmFallbackChain(redisProviderWithCached(null));
        var result = chain.executeWithFallback(
                () -> { throw new RuntimeException("LLM down"); },
                "test-key",
                () -> "structured data");

        assertThat(result.content()).isEqualTo("structured data");
        assertThat(result.source()).isEqualTo(LlmFallbackChain.FallbackResult.Source.STRUCTURED);
    }

    @Test
    void cachesResponseOnLlmSuccess() {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        var ops = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        var chain = new LlmFallbackChain(redisProviderReturning(redis));

        chain.executeWithFallback(
                () -> new ChatCompletion("fresh response"),
                "cache-key",
                () -> "fallback");

        verify(ops).set(eq("llm:cache:cache-key"), eq("fresh response"), eq(Duration.ofMinutes(10)));
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> redisProviderReturning(StringRedisTemplate redis) {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(redis);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<StringRedisTemplate> redisProviderWithCached(String cachedValue) {
        var redis = mock(StringRedisTemplate.class);
        var ops = (ValueOperations<String, String>) mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(cachedValue);
        return redisProviderReturning(redis);
    }
}
