package com.example.demo.repository;

import com.example.demo.event.OutboxEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts a pending outbox row. Must be called inside the business transaction
     * (it joins the caller's transaction, like every other repository here), so the
     * event commits iff the transfer/cancel commits - that is the whole point of
     * the outbox pattern.
     */
    public void append(String eventType, String payload) {
        jdbc.sql("INSERT INTO outbox_event (event_type, payload) VALUES (:type, :payload)")
                .param("type", eventType)
                .param("payload", payload)
                .update();
    }

    /**
     * The unpublished rows whose backoff window has elapsed, oldest first. Creation
     * order is best-effort only: a row deferred by a failed publish can be overtaken
     * by newer rows, which is safe because each event stands alone and the consumer
     * is idempotent.
     */
    public List<OutboxEvent> findDue(int limit) {
        return jdbc.sql("SELECT id, event_type, payload, attempts FROM outbox_event "
                        + "WHERE published_at IS NULL AND next_attempt_at <= NOW() "
                        + "ORDER BY id LIMIT :limit")
                .param("limit", limit)
                .query((rs, rowNum) -> new OutboxEvent(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getString("payload"),
                        rs.getInt("attempts")))
                .list();
    }

    /** Stamps the row as delivered; the relay never picks it up again. */
    public void markPublished(long id) {
        jdbc.sql("UPDATE outbox_event SET published_at = NOW() WHERE id = :id")
                .param("id", id)
                .update();
    }

    /**
     * Records a failed publish: bump {@code attempts} and defer the row with a
     * capped exponential backoff. MySQL applies SET clauses left to right, so the
     * {@code POW(2, attempts)} below sees the already-incremented value: the row is
     * retried after 2s, 4s, 8s, ... capped at 60s.
     */
    public void markFailed(long id) {
        jdbc.sql("UPDATE outbox_event SET attempts = attempts + 1, "
                        + "next_attempt_at = TIMESTAMPADD(SECOND, LEAST(POW(2, attempts), 60), NOW()) "
                        + "WHERE id = :id")
                .param("id", id)
                .update();
    }
}
