package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferCancelIT extends AbstractIntegrationTest {

    private void createUser(String id, int balance) {
        rest.postForEntity("/users", Map.of("userId", id, "initialBalance", balance), String.class);
    }

    @SuppressWarnings("rawtypes")
    private BigDecimal balanceOf(String id) {
        var resp = rest.getForEntity("/users/" + id + "/balance", Map.class);
        return new BigDecimal(resp.getBody().get("balance").toString());
    }

    @SuppressWarnings("rawtypes")
    private long transfer(String from, String to, int amount) {
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", from, "toUserId", to, "amount", amount), Map.class);
        return ((Number) resp.getBody().get("transferId")).longValue();
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> cancel(long transferId) {
        return rest.postForEntity("/transfers/" + transferId + "/cancel", null, Map.class);
    }

    private String statusOf(long transferId) {
        return jdbc.sql("SELECT status FROM transfer WHERE id = :id")
                .param("id", transferId).query(String.class).single();
    }

    private long reversalCount(long transferId) {
        return jdbc.sql("SELECT COUNT(*) FROM transfer WHERE reversal_of = :id")
                .param("id", transferId).query(Long.class).single();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> historyRows(String userId) {
        var body = rest.getForEntity("/transfers?userId=" + userId + "&size=100", Map.class).getBody();
        return (List<Map<String, Object>>) body.get("content");
    }

    @Test
    void cancel_reversesTheTransfer_andReturns200() {
        createUser("alice", 1000);
        createUser("bob", 0);
        long t = transfer("alice", "bob", 200);

        var resp = cancel(t);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("status")).isEqualTo("CANCELLED");
        assertThat(balanceOf("alice")).isEqualByComparingTo("1000"); // money returned
        assertThat(balanceOf("bob")).isEqualByComparingTo("0");
        assertThat(statusOf(t)).isEqualTo("CANCELLED");
        assertThat(reversalCount(t)).isEqualTo(1); // one linked reversal appended

        // History exposes the linkage: original has reversalOf=null, the reversal
        // row points back at the original id (guards the JSON mapping, not just the DB).
        List<Map<String, Object>> rows = historyRows("alice");
        Map<String, Object> original = rows.stream()
                .filter(r -> ((Number) r.get("id")).longValue() == t).findFirst().orElseThrow();
        Map<String, Object> reversal = rows.stream()
                .filter(r -> r.get("reversalOf") != null
                        && ((Number) r.get("reversalOf")).longValue() == t).findFirst().orElseThrow();
        assertThat(original.get("reversalOf")).isNull();
        assertThat(original.get("status")).isEqualTo("CANCELLED");
        assertThat(reversal.get("status")).isEqualTo("COMPLETED");
        assertThat(reversal.get("fromUserId")).isEqualTo("bob"); // amount moves back bob→alice
        assertThat(reversal.get("toUserId")).isEqualTo("alice");
    }

    @Test
    void doubleCancel_isIdempotent_andReversesOnlyOnce() {
        createUser("alice", 1000);
        createUser("bob", 0);
        long t = transfer("alice", "bob", 200);

        assertThat(cancel(t).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancel(t).getStatusCode()).isEqualTo(HttpStatus.OK); // second is a no-op

        assertThat(balanceOf("alice")).isEqualByComparingTo("1000");
        assertThat(balanceOf("bob")).isEqualByComparingTo("0");
        assertThat(reversalCount(t)).isEqualTo(1); // NOT reversed twice
    }

    @Test
    void cancel_returns409_whenReceiverCanNoLongerCover() {
        createUser("alice", 1000);
        createUser("bob", 0);
        createUser("carol", 0);
        long t = transfer("alice", "bob", 200); // bob: 200
        transfer("bob", "carol", 200);          // bob spends it: bob 0, carol 200

        var resp = cancel(t);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // Nothing moved; the original stays COMPLETED (never drove bob negative).
        assertThat(balanceOf("alice")).isEqualByComparingTo("800");
        assertThat(balanceOf("bob")).isEqualByComparingTo("0");
        assertThat(balanceOf("carol")).isEqualByComparingTo("200");
        assertThat(statusOf(t)).isEqualTo("COMPLETED");
        assertThat(reversalCount(t)).isZero();
    }

    @Test
    void cancel_returns409_whenOutsideTheTenMinuteWindow() {
        createUser("alice", 1000);
        createUser("bob", 0);
        // A transfer that "happened" 20 minutes ago (FKs satisfied by the users above).
        jdbc.sql("INSERT INTO transfer (from_user_id, to_user_id, amount, status, created_at) "
                        + "VALUES ('alice', 'bob', 50, 'COMPLETED', NOW() - INTERVAL 20 MINUTE)")
                .update();
        long t = jdbc.sql("SELECT MAX(id) FROM transfer").query(Long.class).single();

        assertThat(cancel(t).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(statusOf(t)).isEqualTo("COMPLETED");
        assertThat(reversalCount(t)).isZero();
    }

    @Test
    void cancel_returns404_whenTransferUnknown() {
        assertThat(cancel(999_999L).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
