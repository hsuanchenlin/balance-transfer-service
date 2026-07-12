package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The centerpiece of the submission (ADR-0001): proves the atomic conditional
 * update holds the money-safety invariant under heavy concurrency — no lost
 * updates, no negative balance, total credit conserved.
 */
class TransferConcurrencyIT extends AbstractIntegrationTest {

    @SuppressWarnings("rawtypes")
    private BigDecimal balanceOf(String id) {
        var resp = rest.getForEntity("/users/" + id + "/balance", Map.class);
        return new BigDecimal(resp.getBody().get("balance").toString());
    }

    private void createUser(String id, int balance) {
        rest.postForEntity("/users", Map.of("userId", id, "initialBalance", balance), String.class);
    }

    private void transferOne(String from, String to, CountDownLatch start, CountDownLatch done,
                             AtomicInteger successes) {
        try {
            start.await();
            var resp = rest.postForEntity("/transfers",
                    Map.of("fromUserId", from, "toUserId", to, "amount", 1), String.class);
            if (resp.getStatusCode() == HttpStatus.CREATED) {
                successes.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            done.countDown();
        }
    }

    @Test
    void concurrentOverspend_neverLosesOrDuplicatesMoney() throws InterruptedException {
        int initial = 100;
        int attempts = 200; // twice the funds — exactly `initial` transfers can succeed
        createUser("sender", initial);
        createUser("receiver", 0);

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attempts);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < attempts; i++) {
            pool.submit(() -> transferOne("sender", "receiver", start, done, successes));
        }
        start.countDown(); // release all at once for maximum contention
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(successes.get()).isEqualTo(initial);                 // no lost updates
        assertThat(balanceOf("sender")).isEqualByComparingTo("0");      // never overspent / negative
        assertThat(balanceOf("receiver")).isEqualByComparingTo("100"); // conservation
    }

    @Test
    void concurrentBidirectional_conservesTotalAndNeverGoesNegative() throws InterruptedException {
        createUser("acctA", 1000);
        createUser("acctB", 1000);
        int perDirection = 300; // net drift bounded well under 1000, so no exhaustion expected

        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(perDirection * 2);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < perDirection; i++) {
            pool.submit(() -> transferOne("acctA", "acctB", start, done, successes)); // A→B
            pool.submit(() -> transferOne("acctB", "acctA", start, done, successes)); // B→A (opposing order)
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        BigDecimal a = balanceOf("acctA");
        BigDecimal b = balanceOf("acctB");
        assertThat(a.add(b)).isEqualByComparingTo("2000");        // total conserved (no deadlock corruption)
        assertThat(a).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(b).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }
}
