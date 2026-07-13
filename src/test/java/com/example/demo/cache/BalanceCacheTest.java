package com.example.demo.cache;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fail-open contract: the cache degrades on Redis failure instead of failing the
 * request, because the DB is the correctness authority (ADR-0001). Without this,
 * a Redis outage would 500 balance reads and even committed transfers (the
 * afterCommit eviction would throw).
 */
class BalanceCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final BalanceCache cache = new BalanceCache(redis);

    private static final RedisConnectionFailureException REDIS_DOWN =
            new RedisConnectionFailureException("connection refused");

    @Test
    void get_returnsMissWhenRedisIsDown() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("balance:alice")).thenThrow(REDIS_DOWN);

        assertThat(cache.get("alice")).isEmpty();
    }

    @Test
    void put_swallowsRedisFailure() {
        when(redis.opsForValue()).thenReturn(values);
        doThrow(REDIS_DOWN).when(values).set(anyString(), anyString(), any(Duration.class));

        assertThatCode(() -> cache.put("alice", new BigDecimal("10.00")))
                .doesNotThrowAnyException();
    }

    @Test
    void evict_swallowsRedisFailure() {
        when(redis.delete("balance:alice")).thenThrow(REDIS_DOWN);

        assertThatCode(() -> cache.evict("alice")).doesNotThrowAnyException();
    }

    @Test
    void get_parsesCachedValueOnHit() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get("balance:alice")).thenReturn("42.5000");

        assertThat(cache.get("alice")).contains(new BigDecimal("42.5000"));
    }
}
