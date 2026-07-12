package com.example.demo.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class AccountRepository {

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String userId, BigDecimal balance) {
        jdbc.sql("INSERT INTO account (user_id, balance) VALUES (:userId, :balance)")
                .param("userId", userId)
                .param("balance", balance)
                .update();
    }

    public Optional<BigDecimal> findBalance(String userId) {
        return jdbc.sql("SELECT balance FROM account WHERE user_id = :userId")
                .param("userId", userId)
                .query(BigDecimal.class)
                .optional();
    }
}
