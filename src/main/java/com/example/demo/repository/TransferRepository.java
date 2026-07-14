package com.example.demo.repository;

import com.example.demo.model.TransferHistoryItem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
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

    /**
     * Appends the compensating reversal for a cancelled transfer (ticket 08): a
     * COMPLETED movement in the opposite direction, linked to the original via
     * {@code reversal_of}. History stays append-only — nothing is deleted.
     */
    public long insertReversal(String fromUserId, String toUserId, BigDecimal amount, long reversalOf) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO transfer (from_user_id, to_user_id, amount, status, reversal_of) "
                        + "VALUES (:from, :to, :amount, 'COMPLETED', :reversalOf)")
                .param("from", fromUserId)
                .param("to", toUserId)
                .param("amount", amount)
                .param("reversalOf", reversalOf)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    /**
     * The transfer recorded under an idempotency key, with its full payload so
     * the replay path can verify the retry matches the original request.
     */
    public Optional<TransferHistoryItem> findByRequestId(String requestId) {
        return jdbc.sql("SELECT id, from_user_id, to_user_id, amount, status, reversal_of, created_at "
                        + "FROM transfer WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(TransferRepository::mapItem)
                .optional();
    }

    public Optional<TransferHistoryItem> findById(long id) {
        return jdbc.sql("SELECT id, from_user_id, to_user_id, amount, status, reversal_of, created_at "
                        + "FROM transfer WHERE id = :id")
                .param("id", id)
                .query(TransferRepository::mapItem)
                .optional();
    }

    /**
     * Locking read used to classify a failed cancel: {@code FOR UPDATE} sees the
     * latest committed row (not the transaction's snapshot), so a concurrent
     * double-cancel observes the {@code CANCELLED} status the winner committed.
     */
    public Optional<TransferHistoryItem> findByIdForUpdate(long id) {
        return jdbc.sql("SELECT id, from_user_id, to_user_id, amount, status, reversal_of, created_at "
                        + "FROM transfer WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(TransferRepository::mapItem)
                .optional();
    }

    /**
     * Transfers the user was involved in as sender <em>or</em> receiver, newest
     * first, one page at a time. {@code created_at DESC, id DESC} gives a stable
     * total order even when rows share a timestamp.
     *
     * <p>The {@code OR} predicate caps out at an index merge; at scale the
     * evolution is a {@code UNION ALL} over the two indexed halves plus keyset
     * pagination (see README, "Known limits and scale evolutions").
     */
    public List<TransferHistoryItem> listByUser(String userId, int size, long offset) {
        return jdbc.sql("SELECT id, from_user_id, to_user_id, amount, status, reversal_of, created_at "
                        + "FROM transfer WHERE from_user_id = :userId OR to_user_id = :userId "
                        + "ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("userId", userId)
                .param("size", size)
                .param("offset", offset)
                .query(TransferRepository::mapItem)
                .list();
    }

    public long countByUser(String userId) {
        return jdbc.sql("SELECT COUNT(*) FROM transfer "
                        + "WHERE from_user_id = :userId OR to_user_id = :userId")
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /**
     * Guarded status flip encoding ADR-0002: a transfer can be cancelled only
     * while it is still COMPLETED, within the 10-minute window, and is an
     * original transfer ({@code reversal_of IS NULL}) — a compensating reversal
     * row is never itself cancellable. Returns rows affected — 0 means already
     * cancelled, too old, a reversal, or gone, which makes a double-cancel
     * naturally idempotent.
     */
    public int markCancelled(long id) {
        return jdbc.sql("UPDATE transfer SET status = 'CANCELLED' "
                        + "WHERE id = :id AND status = 'COMPLETED' "
                        + "AND reversal_of IS NULL "
                        + "AND created_at > NOW() - INTERVAL 10 MINUTE")
                .param("id", id)
                .update();
    }

    private static TransferHistoryItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        // Read reversal_of and capture wasNull() immediately — wasNull() reflects the
        // most recent column read, so it must be checked before any other getXxx call.
        long reversalOfRaw = rs.getLong("reversal_of");
        Long reversalOf = rs.wasNull() ? null : reversalOfRaw;
        return new TransferHistoryItem(
                rs.getLong("id"),
                rs.getString("from_user_id"),
                rs.getString("to_user_id"),
                rs.getBigDecimal("amount"),
                rs.getString("status"),
                reversalOf,
                rs.getTimestamp("created_at").toInstant());
    }
}
