package com.example.demo;

import com.example.demo.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UserEndpointIT extends AbstractIntegrationTest {

    @Test
    void createUser_returns201_forNewUser() {
        var response = rest.postForEntity(
                "/users",
                Map.of("userId", "user_001", "initialBalance", 1000),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @SuppressWarnings("rawtypes")
    void getBalance_returnsInitialBalance_afterCreate() {
        rest.postForEntity("/users",
                Map.of("userId", "user_002", "initialBalance", 1000), String.class);

        var response = rest.getForEntity("/users/user_002/balance", Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new BigDecimal(response.getBody().get("balance").toString()))
                .isEqualByComparingTo("1000");
    }

    @Test
    void createUser_returns409_forDuplicateUserId() {
        rest.postForEntity("/users",
                Map.of("userId", "user_003", "initialBalance", 500), String.class);

        var duplicate = rest.postForEntity("/users",
                Map.of("userId", "user_003", "initialBalance", 999), String.class);

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void getBalance_returns404_forUnknownUser() {
        var response = rest.getForEntity("/users/does_not_exist/balance", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void createUser_returns400_forNegativeInitialBalance() {
        var response = rest.postForEntity(
                "/users",
                Map.of("userId", "user_004", "initialBalance", -5),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
