package com.example.demo.repository;

import com.example.demo.model.TransferResponse;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Optional;

@Repository
public class TransferRepository {

    private final JdbcClient jdbc;

    public TransferRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends a COMPLETED transfer row and returns its generated id. A non-null
     * {@code requestId} is stored under a UNIQUE constraint — a duplicate raises
     * {@code DuplicateKeyException}, which is how concurrent retries are rejected.
     */
    public long insertCompleted(String fromUserId, String toUserId, BigDecimal amount, String requestId) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO transfer (from_user_id, to_user_id, amount, status, request_id) "
                        + "VALUES (:from, :to, :amount, 'COMPLETED', :requestId)")
                .param("from", fromUserId)
                .param("to", toUserId)
                .param("amount", amount)
                .param("requestId", requestId)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    public Optional<TransferResponse> findByRequestId(String requestId) {
        return jdbc.sql("SELECT id, status FROM transfer WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query((rs, rowNum) -> new TransferResponse(rs.getLong("id"), rs.getString("status")))
                .optional();
    }
}
