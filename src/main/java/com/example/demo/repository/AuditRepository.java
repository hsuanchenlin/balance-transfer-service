package com.example.demo.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcClient jdbc;

    public AuditRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotent: a redelivered event (same type + transferId) is ignored by the UNIQUE key. */
    public void recordOnce(String eventType, long transferId, String payload) {
        jdbc.sql("INSERT IGNORE INTO audit_log (event_type, transfer_id, payload) "
                        + "VALUES (:type, :transferId, :payload)")
                .param("type", eventType)
                .param("transferId", transferId)
                .param("payload", payload)
                .update();
    }

    public int countByTransferId(long transferId) {
        return jdbc.sql("SELECT COUNT(*) FROM audit_log WHERE transfer_id = :transferId")
                .param("transferId", transferId)
                .query(Integer.class)
                .single();
    }
}
