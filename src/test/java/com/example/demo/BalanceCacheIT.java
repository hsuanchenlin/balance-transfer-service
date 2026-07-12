package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BalanceCacheIT extends AbstractIntegrationTest {

    private void createUser(String id, int balance) {
        rest.postForEntity("/users", Map.of("userId", id, "initialBalance", balance), String.class);
    }

    @SuppressWarnings("rawtypes")
    private ResponseEntity<Map> getBalance(String id) {
        return rest.getForEntity("/users/" + id + "/balance", Map.class);
    }

    @SuppressWarnings("rawtypes")
    private BigDecimal balance(ResponseEntity<Map> resp) {
        return new BigDecimal(resp.getBody().get("balance").toString());
    }

    @Test
    void balanceRead_isServedFromCache_withoutQueryingDb() {
        createUser("cached", 500);
        assertThat(balance(getBalance("cached"))).isEqualByComparingTo("500"); // primes cache

        // Remove the row behind the app's back — only a cache hit can still answer.
        jdbc.sql("DELETE FROM account WHERE user_id = :id").param("id", "cached").update();

        var second = getBalance("cached");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(balance(second)).isEqualByComparingTo("500");
    }

    @Test
    void transfer_invalidatesCachedBalance() {
        createUser("s", 1000);
        createUser("r", 0);
        assertThat(balance(getBalance("s"))).isEqualByComparingTo("1000"); // primes cache

        rest.postForEntity("/transfers",
                Map.of("fromUserId", "s", "toUserId", "r", "amount", 100), String.class);

        assertThat(balance(getBalance("s"))).isEqualByComparingTo("900"); // cache invalidated → fresh read
    }
}
