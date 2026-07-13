package com.example.demo.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/**
 * Read-path cache for account balances (ADR-0001: Redis stays on the read path,
 * never the write authority). Entries carry a TTL so any missed invalidation
 * self-heals.
 *
 * <p>Fail-open: because the DB is the correctness authority, a Redis outage must
 * never fail a request. Every operation catches {@link DataAccessException} and
 * degrades - a failed read is a cache miss, a failed write or eviction is skipped
 * (the TTL bounds the staleness a skipped eviction can cause).
 */
@Component
public class BalanceCache {

    private static final Logger log = LoggerFactory.getLogger(BalanceCache.class);

    private static final Duration TTL = Duration.ofMinutes(5);

    private final StringRedisTemplate redis;

    public BalanceCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private String key(String userId) {
        return "balance:" + userId;
    }

    public Optional<BigDecimal> get(String userId) {
        try {
            String value = redis.opsForValue().get(key(userId));
            return value == null ? Optional.empty() : Optional.of(new BigDecimal(value));
        } catch (DataAccessException e) {
            log.warn("Balance cache read failed for {}; treating as a miss", userId, e);
            return Optional.empty();
        }
    }

    public void put(String userId, BigDecimal balance) {
        try {
            redis.opsForValue().set(key(userId), balance.toPlainString(), TTL);
        } catch (DataAccessException e) {
            log.warn("Balance cache write failed for {}; skipping", userId, e);
        }
    }

    public void evict(String userId) {
        try {
            redis.delete(key(userId));
        } catch (DataAccessException e) {
            log.warn("Balance cache eviction failed for {}; entry stale until TTL", userId, e);
        }
    }
}
