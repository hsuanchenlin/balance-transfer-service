package com.example.demo.cache;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Read-path cache for account balances (ADR-0001: Redis stays on the read path,
 * never the write authority). Entries carry a TTL so any missed invalidation
 * self-heals.
 */
@Component
public class BalanceCache {

    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public BalanceCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String userId) {
        return "balance:" + userId;
    }

    public Optional<BigDecimal> get(String userId) {
        String value = redis.opsForValue().get(key(userId));
        return value == null ? Optional.empty() : Optional.of(new BigDecimal(value));
    }

    public void put(String userId, BigDecimal balance) {
        redis.opsForValue().set(key(userId), balance.toPlainString(), TTL);
    }

    public void evict(String userId) {
        redis.delete(key(userId));
    }
}
