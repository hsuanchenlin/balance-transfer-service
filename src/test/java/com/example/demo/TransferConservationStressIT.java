package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whole-system conservation stress test, complementing {@link TransferConcurrencyIT}
 * (which hammers fixed pairs). Here a bounded executor fires a storm of transfers
 * between RANDOM account pairs with RANDOM amounts - the workload an exchange
 * actually sees - and afterwards the invariants that money-safety rests on are
 * checked against the authoritative store (ADR-0001):
 *
 * <ul>
 *   <li>conservation - the sum of all balances still equals the seeded total;</li>
 *   <li>credit invariant - no balance ever ends negative;</li>
 *   <li>audit consistency - the ledger ({@code transfer} table) records exactly
 *       one COMPLETED row per successful (201) response, and replaying those rows
 *       over the seed reproduces every account's final balance;</li>
 *   <li>at-most-once - concurrent identical retries (same requestId + payload)
 *       apply exactly one transfer.</li>
 * </ul>
 *
 * <p>The attempt schedule is pre-generated from a fixed seed so inputs are
 * reproducible; assertions are interleaving-independent invariants, so the test
 * is deterministic regardless of how the storm actually races.
 */
class TransferConservationStressIT extends AbstractIntegrationTest {

    private static final int THREADS = 32;
    private static final long AWAIT_SECONDS = 75;

    /** One pre-generated storm attempt; {@code from == to} exercises self-transfer rejection. */
    private record Attempt(String from, String to, int amount, String requestId) {
        boolean selfTransfer() {
            return from.equals(to);
        }
    }

    /** What one attempt actually got back over HTTP. */
    private record Outcome(int status, Object transferId, String rawBody) {
    }

    private void createUser(String id, int balance) {
        var resp = rest.postForEntity("/users", Map.of("userId", id, "initialBalance", balance), String.class);
        assertThat(resp.getStatusCode())
                .as("seeding account %s failed: %s", id, resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    @SuppressWarnings("rawtypes")
    private BigDecimal apiBalanceOf(String id) {
        var resp = rest.getForEntity("/users/" + id + "/balance", Map.class);
        return new BigDecimal(resp.getBody().get("balance").toString());
    }

    @SuppressWarnings("rawtypes")
    private Outcome postTransfer(String from, String to, int amount, String requestId) {
        var body = new HashMap<String, Object>();
        body.put("fromUserId", from);
        body.put("toUserId", to);
        body.put("amount", amount);
        body.put("requestId", requestId);
        ResponseEntity<Map> resp = rest.postForEntity("/transfers", body, Map.class);
        Object transferId = resp.getBody() == null ? null : resp.getBody().get("transferId");
        return new Outcome(resp.getStatusCode().value(), transferId, String.valueOf(resp.getBody()));
    }

    /** Releases all queued tasks at once and waits for them with a hard deadline. */
    private void runStorm(ExecutorService pool, CountDownLatch start, CountDownLatch done)
            throws InterruptedException {
        start.countDown();
        try {
            boolean finished = done.await(AWAIT_SECONDS, TimeUnit.SECONDS);
            assertThat(finished)
                    .as("storm did not finish within %ds - %d task(s) still outstanding "
                            + "(possible deadlock or connection-pool starvation)",
                            AWAIT_SECONDS, done.getCount())
                    .isTrue();
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS))
                    .as("executor did not terminate after the storm completed")
                    .isTrue();
        } finally {
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void randomStorm_conservesTotal_neverNegative_andLedgerMatchesResponses() throws InterruptedException {
        // Seed 5 accounts; deliberately uneven so some drain and hit insufficient-funds.
        List<String> ids = List.of("acct0", "acct1", "acct2", "acct3", "acct4");
        int[] seedBalances = {400, 250, 150, 120, 80};
        int seededTotal = 1000;
        for (int i = 0; i < ids.size(); i++) {
            createUser(ids.get(i), seedBalances[i]);
        }

        // Pre-generate the whole schedule from a fixed seed: reproducible inputs,
        // random pairs (from == to happens ~20% of the time and must be rejected),
        // random amounts, one unique requestId per attempt.
        int attemptCount = 320;
        Random rnd = new Random(20260714L);
        List<Attempt> attempts = new ArrayList<>(attemptCount);
        for (int i = 0; i < attemptCount; i++) {
            String from = ids.get(rnd.nextInt(ids.size()));
            String to = ids.get(rnd.nextInt(ids.size()));
            attempts.add(new Attempt(from, to, 1 + rnd.nextInt(90), "storm-" + i));
        }

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(attemptCount);
        Outcome[] outcomes = new Outcome[attemptCount];
        for (int i = 0; i < attemptCount; i++) {
            final int idx = i;
            final Attempt a = attempts.get(i);
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes[idx] = postTransfer(a.from(), a.to(), a.amount(), a.requestId());
                } catch (Exception e) {
                    // Transport-level failure (not an HTTP status) - record and fail below.
                    outcomes[idx] = new Outcome(-1, null, e.toString());
                } finally {
                    done.countDown();
                }
            });
        }
        runStorm(pool, start, done);

        // Triage every outcome. Valid per attempt: 201 (applied), 409 insufficient
        // funds (only between distinct accounts; requestIds are unique so no other
        // 409 source exists), 400 self-transfer (only when from == to). Anything
        // else - 5xx, deadlock surfacing as an error, transport failure - fails.
        List<String> unexpected = new ArrayList<>();
        List<Integer> succeeded = new ArrayList<>();
        for (int i = 0; i < attemptCount; i++) {
            Attempt a = attempts.get(i);
            Outcome o = outcomes[i];
            boolean valid = switch (o.status()) {
                case 201 -> !a.selfTransfer();
                case 409 -> !a.selfTransfer();
                case 400 -> a.selfTransfer();
                default -> false;
            };
            if (valid) {
                if (o.status() == 201) {
                    succeeded.add(i);
                }
            } else {
                unexpected.add("attempt %d (%s -> %s, amount %d): HTTP %d, body %s"
                        .formatted(i, a.from(), a.to(), a.amount(), o.status(), o.rawBody()));
            }
        }
        assertThat(unexpected)
                .as("every attempt must resolve to success, insufficient-funds, or "
                        + "self-transfer rejection; got %d other outcome(s)", unexpected.size())
                .isEmpty();
        // Vacuity guard: with 1000 total across 5 accounts and amounts <= 90, a
        // storm where nothing applied would mean the endpoint is broken.
        assertThat(succeeded).as("storm applied no transfers at all - nothing was stress-tested").isNotEmpty();

        // Conservation: the storm moved money around but created/destroyed none.
        BigDecimal dbTotal = jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM account")
                .query(BigDecimal.class).single();
        assertThat(dbTotal)
                .as("sum of balances after the storm must equal the seeded total")
                .isEqualByComparingTo(BigDecimal.valueOf(seededTotal));

        // Credit invariant: the conditional debit must never have let anyone go negative.
        BigDecimal minBalance = jdbc.sql("SELECT MIN(balance) FROM account")
                .query(BigDecimal.class).single();
        assertThat(minBalance)
                .as("no account may end the storm with a negative balance")
                .isGreaterThanOrEqualTo(BigDecimal.ZERO);

        // Audit consistency: the ledger recorded exactly one COMPLETED row per 201,
        // each with a distinct transfer id.
        long completedRows = jdbc.sql("SELECT COUNT(*) FROM transfer WHERE status = 'COMPLETED'")
                .query(Long.class).single();
        assertThat(completedRows)
                .as("COMPLETED ledger rows must match successful (201) responses")
                .isEqualTo(succeeded.size());
        long distinctIds = succeeded.stream().map(i -> outcomes[i].transferId()).distinct().count();
        assertThat(distinctIds)
                .as("every successful response must carry a distinct transferId")
                .isEqualTo(succeeded.size());

        // Strongest ledger check: replaying only the successful attempts over the
        // seed must reproduce every account's final balance exactly.
        Map<String, Long> expected = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            expected.put(ids.get(i), (long) seedBalances[i]);
        }
        for (int i : succeeded) {
            Attempt a = attempts.get(i);
            expected.merge(a.from(), (long) -a.amount(), Long::sum);
            expected.merge(a.to(), (long) a.amount(), Long::sum);
        }
        Map<String, BigDecimal> dbBalances = jdbc.sql("SELECT user_id, balance FROM account")
                .query((rs, rowNum) -> Map.entry(rs.getString("user_id"), rs.getBigDecimal("balance")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (String id : ids) {
            assertThat(dbBalances.get(id))
                    .as("account %s: DB balance must equal seed replayed with the successful transfers", id)
                    .isEqualByComparingTo(BigDecimal.valueOf(expected.get(id)));
            // The read path (Redis cache-aside, fail-open) must agree with the DB
            // now that every post-commit eviction has run.
            assertThat(apiBalanceOf(id))
                    .as("account %s: API-visible balance must match the DB after the storm", id)
                    .isEqualByComparingTo(dbBalances.get(id));
        }
    }

    @Test
    void concurrentIdenticalRetries_applyExactlyOncePerRequestId() throws InterruptedException {
        createUser("idemA", 100);
        createUser("idemB", 0);

        // 5 requestIds x 8 identical submissions each, all released together so the
        // duplicates race the original inside the DB, not after it.
        int keys = 5;
        int submittersPerKey = 8;
        int amount = 7;
        int total = keys * submittersPerKey;

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(total);
        Outcome[] outcomes = new Outcome[total];
        for (int k = 0; k < keys; k++) {
            final String requestId = "retry-" + k;
            for (int s = 0; s < submittersPerKey; s++) {
                final int idx = k * submittersPerKey + s;
                pool.submit(() -> {
                    try {
                        start.await();
                        outcomes[idx] = postTransfer("idemA", "idemB", amount, requestId);
                    } catch (Exception e) {
                        outcomes[idx] = new Outcome(-1, null, e.toString());
                    } finally {
                        done.countDown();
                    }
                });
            }
        }
        runStorm(pool, start, done);

        for (int k = 0; k < keys; k++) {
            String requestId = "retry-" + k;
            List<Outcome> group = new ArrayList<>();
            for (int s = 0; s < submittersPerKey; s++) {
                group.add(outcomes[k * submittersPerKey + s]);
            }
            // Each identical retry either won/replayed (201) or lost the race on the
            // unique key (409). Nothing else - no 5xx, no 422 (payloads are identical).
            assertThat(group)
                    .as("requestId %s: every concurrent identical retry must yield 201 or 409, got %s",
                            requestId, group)
                    .allMatch(o -> o.status() == 201 || o.status() == 409);
            List<Object> successIds = group.stream()
                    .filter(o -> o.status() == 201)
                    .map(Outcome::transferId)
                    .toList();
            assertThat(successIds)
                    .as("requestId %s: at least one submission must succeed", requestId)
                    .isNotEmpty();
            assertThat(successIds.stream().distinct().count())
                    .as("requestId %s: all 201 responses must replay the same transferId", requestId)
                    .isEqualTo(1);
            long rows = jdbc.sql("SELECT COUNT(*) FROM transfer WHERE request_id = :rid")
                    .param("rid", requestId)
                    .query(Long.class).single();
            assertThat(rows)
                    .as("requestId %s: the ledger must hold exactly one row for the key", requestId)
                    .isEqualTo(1);
        }

        // Each of the 5 keys applied exactly once: 5 x 7 moved, total conserved.
        assertThat(apiBalanceOf("idemA")).isEqualByComparingTo(BigDecimal.valueOf(100 - keys * amount));
        assertThat(apiBalanceOf("idemB")).isEqualByComparingTo(BigDecimal.valueOf(keys * amount));
        BigDecimal dbTotal = jdbc.sql("SELECT COALESCE(SUM(balance), 0) FROM account")
                .query(BigDecimal.class).single();
        assertThat(dbTotal)
                .as("concurrent retries must conserve the seeded total")
                .isEqualByComparingTo(BigDecimal.valueOf(100));
    }
}
