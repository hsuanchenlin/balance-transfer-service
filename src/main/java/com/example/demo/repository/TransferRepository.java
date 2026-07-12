package com.example.demo.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;

@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Appends a COMPLETED transfer row and returns its generated id. */
    public long insertCompleted(String fromUserId, String toUserId, BigDecimal amount) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO transfer (from_user_id, to_user_id, amount, status) "
                        + "VALUES (:from, :to, :amount, 'COMPLETED')")
                .param("from", fromUserId)
                .param("to", toUserId)
                .param("amount", amount)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }
}
