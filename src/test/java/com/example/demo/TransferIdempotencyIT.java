package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TransferIdempotencyIT extends AbstractIntegrationTest {

    private void createUser(String id, int balance) {
        rest.postForEntity("/users", Map.of("userId", id, "initialBalance", balance), String.class);
    }

    @SuppressWarnings("rawtypes")
    private BigDecimal balanceOf(String id) {
        var resp = rest.getForEntity("/users/" + id + "/balance", Map.class);
        return new BigDecimal(resp.getBody().get("balance").toString());
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> transfer(String from, String to, int amount, String requestId) {
        var body = new HashMap<String, Object>();
        body.put("fromUserId", from);
        body.put("toUserId", to);
        body.put("amount", amount);
        if (requestId != null) {
            body.put("requestId", requestId);
        }
        return rest.postForEntity("/transfers", body, Map.class);
    }

    @Test
    void duplicateRequestId_sequential_appliesOnce_andReplaysOriginal() {
        createUser("s", 1000);
        createUser("r", 0);

        var first = transfer("s", "r", 100, "req-1");
        var second = transfer("s", "r", 100, "req-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        // Transfer applied exactly once, not twice.
        assertThat(balanceOf("s")).isEqualByComparingTo("900");
        assertThat(balanceOf("r")).isEqualByComparingTo("100");
        // Replay returns the original transfer's id.
        assertThat(second.getBody().get("transferId"))
                .isEqualTo(first.getBody().get("transferId"));
    }

    @Test
    void duplicateRequestId_concurrent_appliesExactlyOnce() throws InterruptedException {
        createUser("s", 1000);
        createUser("r", 0);
        int threads = 16;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    transfer("s", "r", 100, "dup-key");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // Exactly one of the 16 identical requests changed balances.
        assertThat(balanceOf("s")).isEqualByComparingTo("900");
        assertThat(balanceOf("r")).isEqualByComparingTo("100");
    }
}
