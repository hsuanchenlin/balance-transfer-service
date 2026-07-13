package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TransferEndpointIT extends AbstractIntegrationTest {

    private void createUser(String id, int balance) {
        rest.postForEntity("/users",
                Map.of("userId", id, "initialBalance", balance), String.class);
    }

    @SuppressWarnings("rawtypes")
    private BigDecimal balanceOf(String id) {
        var resp = rest.getForEntity("/users/" + id + "/balance", Map.class);
        return new BigDecimal(resp.getBody().get("balance").toString());
    }

    private HttpStatus transfer(String from, String to, Object amount) {
        return (HttpStatus) rest.postForEntity("/transfers",
                Map.of("fromUserId", from, "toUserId", to, "amount", amount),
                String.class).getStatusCode();
    }

    @Test
    void transfer_movesBalance_andReturns201() {
        createUser("alice", 1000);
        createUser("bob", 0);

        var resp = rest.postForEntity("/transfers",
                Map.of("fromUserId", "alice", "toUserId", "bob", "amount", 150),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(balanceOf("alice")).isEqualByComparingTo("850");
        assertThat(balanceOf("bob")).isEqualByComparingTo("150");
    }

    @Test
    void transfer_returns409_whenInsufficientFunds() {
        createUser("poor", 10);
        createUser("rich", 0);

        assertThat(transfer("poor", "rich", 50)).isEqualTo(HttpStatus.CONFLICT);
        assertThat(balanceOf("poor")).isEqualByComparingTo("10"); // unchanged
    }

    @Test
    void transfer_returns404_whenSenderUnknown() {
        createUser("known", 100);
        assertThat(transfer("ghost", "known", 10)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void transfer_returns404_whenReceiverUnknown() {
        createUser("known", 100);
        assertThat(transfer("known", "ghost", 10)).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void transfer_returns400_whenSelfTransfer() {
        createUser("same", 100);
        assertThat(transfer("same", "same", 10)).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void transfer_returns400_whenAmountNotPositive() {
        createUser("a", 100);
        createUser("b", 0);
        assertThat(transfer("a", "b", 0)).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(transfer("a", "b", -5)).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
