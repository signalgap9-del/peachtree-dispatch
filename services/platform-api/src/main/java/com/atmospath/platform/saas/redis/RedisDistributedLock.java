package com.atmospath.platform.saas.redis;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Minimal SET NX EX lock for subscription mutations. The unlock script
 * compares the stored token so an expired lock can never be released by the
 * process that lost it.
 */
@Component
@ConditionalOnProperty(name = "atmospath.saas.enabled", havingValue = "true")
public class RedisDistributedLock {

    public static final Duration SUBSCRIPTION_LOCK_TTL = Duration.ofSeconds(30);

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public record LockHandle(String key, String token) {
    }

    /**
     * Tries to acquire {@code lock:<resource>} with the given TTL.
     * Subscription callers use resource {@code subscription:<subscriptionId>}.
     */
    public Optional<LockHandle> tryLock(String resource, Duration ttl) {
        String key = "lock:" + resource;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
        if (Boolean.TRUE.equals(acquired)) {
            return Optional.of(new LockHandle(key, token));
        }
        log.debug("Lock busy: {}", key);
        return Optional.empty();
    }

    public boolean unlock(LockHandle handle) {
        Long deleted = redis.execute(UNLOCK_SCRIPT, List.of(handle.key()), handle.token());
        return deleted != null && deleted == 1L;
    }
}
