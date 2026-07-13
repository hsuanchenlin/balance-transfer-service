package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferHistoryIT extends AbstractIntegrationTest {

    private void createUser(String id, int balance) {
        rest.postForEntity("/users", Map.of("userId", id, "initialBalance", balance), String.class);
    }

    @SuppressWarnings("rawtypes")
    private long transfer(String from, String to, int amount) {
        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", from, "toUserId", to, "amount", amount), Map.class);
        return ((Number) resp.getBody().get("transferId")).longValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> history(String userId, String query) {
        return rest.getForEntity("/transfers?userId=" + userId + query, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Long> idsOf(Map<String, Object> page) {
        return ((List<Map<String, Object>>) page.get("content")).stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .toList();
    }

    @Test
    void history_returnsUsersTransfers_newestFirst_asSenderOrReceiver() {
        createUser("alice", 1000);
        createUser("bob", 0);
        createUser("carol", 500);

        long t1 = transfer("alice", "bob", 100);   // alice sends
        long t2 = transfer("carol", "alice", 50);   // alice receives
        long t3 = transfer("alice", "bob", 30);     // alice sends
        transfer("carol", "bob", 10);               // alice NOT involved

        Map<String, Object> page = history("alice", "");

        // Newest first (created_at DESC, id DESC); the carol→bob transfer is excluded.
        assertThat(idsOf(page)).containsExactly(t3, t2, t1);
        assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(3);
    }

    @Test
    void history_isPaged_withStableOrderAcrossPages() {
        createUser("alice", 1000);
        createUser("bob", 0);

        long t1 = transfer("alice", "bob", 10);
        long t2 = transfer("alice", "bob", 20);
        long t3 = transfer("alice", "bob", 30);

        Map<String, Object> first = history("alice", "&page=0&size=2");
        Map<String, Object> second = history("alice", "&page=1&size=2");

        assertThat(idsOf(first)).containsExactly(t3, t2);
        assertThat(idsOf(second)).containsExactly(t1);
        assertThat(((Number) first.get("totalElements")).longValue()).isEqualTo(3);
    }

    @Test
    void history_returnsEmptyPage_forUserWithNoTransfers() {
        createUser("loner", 100);

        Map<String, Object> page = history("loner", "");

        assertThat(idsOf(page)).isEmpty();
        assertThat(((Number) page.get("totalElements")).longValue()).isZero();
    }

    @Test
    void history_returns400_onBadPagingOrMissingUser() {
        assertThat(rest.getForEntity("/transfers?userId=x&size=0", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/transfers?userId=x&size=1000", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/transfers?userId=x&page=-1", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(rest.getForEntity("/transfers", String.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
