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
    private ResponseEntity<Map> transfer(String from, String to, Object amount, String requestId) {
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
    void sameRequestId_differentAmount_rejected422_movesNoMoney() {
        createUser("s", 1000);
        createUser("r", 0);

        var first = transfer("s", "r", 100, "req-mismatch");
        var reused = transfer("s", "r", 250, "req-mismatch");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        // ApiError shape, with the offending key called out.
        assertThat(reused.getBody().get("status")).isEqualTo(422);
        assertThat(reused.getBody().get("message").toString()).contains("req-mismatch");
        // Only the original transfer moved money.
        assertThat(balanceOf("s")).isEqualByComparingTo("900");
        assertThat(balanceOf("r")).isEqualByComparingTo("100");
    }

    @Test
    void sameRequestId_differentParties_rejected422() {
        createUser("s", 1000);
        createUser("r", 0);

        transfer("s", "r", 100, "req-parties");
        var reversedParties = transfer("r", "s", 100, "req-parties");

        assertThat(reversedParties.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(balanceOf("s")).isEqualByComparingTo("900");
        assertThat(balanceOf("r")).isEqualByComparingTo("100");
    }

    @Test
    void sameRequestId_samePayloadDifferentScale_replaysOriginal() {
        createUser("s", 1000);
        createUser("r", 0);

        var first = transfer("s", "r", 100, "req-scale");
        // 100.00 is the same amount as 100 - scale must not break the replay.
        var second = transfer("s", "r", new BigDecimal("100.00"), "req-scale");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getBody().get("transferId"))
                .isEqualTo(first.getBody().get("transferId"));
        assertThat(balanceOf("s")).isEqualByComparingTo("900");
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
