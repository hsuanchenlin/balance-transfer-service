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

    public boolean exists(String userId) {
        return jdbc.sql("SELECT COUNT(*) FROM account WHERE user_id = :userId")
                .param("userId", userId)
                .query(Integer.class)
                .single() > 0;
    }

    /**
     * Atomic conditional debit (ADR-0001): decrements only if the balance can
     * cover it. Returns rows affected — 0 means insufficient funds. InnoDB's row
     * lock makes this safe under concurrency and across app instances.
     */
    public int debit(String userId, BigDecimal amount) {
        return jdbc.sql("UPDATE account SET balance = balance - :amount "
                        + "WHERE user_id = :userId AND balance >= :amount")
                .param("userId", userId)
                .param("amount", amount)
                .update();
    }

    public int credit(String userId, BigDecimal amount) {
        return jdbc.sql("UPDATE account SET balance = balance + :amount WHERE user_id = :userId")
                .param("userId", userId)
                .param("amount", amount)
                .update();
    }
}
