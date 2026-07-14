-- Balance Transfer Service schema (ticket 01)
-- Runs automatically on first MySQL container init (mounted by docker-compose).

CREATE TABLE IF NOT EXISTS account (
    user_id    VARCHAR(64)   NOT NULL,
    balance    DECIMAL(19,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    CONSTRAINT chk_account_balance_non_negative CHECK (balance >= 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS transfer (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    from_user_id VARCHAR(64)   NOT NULL,
    to_user_id   VARCHAR(64)   NOT NULL,
    amount       DECIMAL(19,4) NOT NULL,
    status       VARCHAR(16)   NOT NULL,           -- COMPLETED | CANCELLED
    request_id   VARCHAR(64)   NULL,               -- idempotency key (ticket 04); NULLs allowed and not deduped
    reversal_of  BIGINT        NULL,               -- set on a reversal, points at the original transfer (ticket 08)
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_transfer_request_id (request_id),
    KEY idx_transfer_from (from_user_id),
    KEY idx_transfer_to (to_user_id),
    KEY idx_transfer_created_at (created_at),
    CONSTRAINT chk_transfer_amount_positive CHECK (amount > 0),
    CONSTRAINT fk_transfer_from FOREIGN KEY (from_user_id) REFERENCES account (user_id),
    CONSTRAINT fk_transfer_to FOREIGN KEY (to_user_id) REFERENCES account (user_id),
    CONSTRAINT fk_transfer_reversal FOREIGN KEY (reversal_of) REFERENCES transfer (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Async side-effect log written by the RocketMQ consumer (ticket 06).
-- UNIQUE (event_type, transfer_id) makes redelivery idempotent via INSERT IGNORE.
CREATE TABLE IF NOT EXISTS audit_log (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    event_type  VARCHAR(32)  NOT NULL,
    transfer_id BIGINT       NOT NULL,
    payload     TEXT         NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_audit_event (event_type, transfer_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Transactional outbox for RocketMQ publishing (at-least-once). A row is inserted
-- in the SAME transaction as the transfer/cancel it describes, so the event exists
-- iff the business change committed. The relay (event/OutboxRelay) polls unpublished
-- rows in id order, sends them to RocketMQ, and stamps published_at; a failed send
-- bumps attempts and defers the row via next_attempt_at (capped exponential backoff).
CREATE TABLE IF NOT EXISTS outbox_event (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    event_type      VARCHAR(32) NOT NULL,               -- TransferCompleted | TransferCancelled (RocketMQ tag)
    payload         TEXT        NOT NULL,               -- event DTO as JSON, the exact message body
    attempts        INT         NOT NULL DEFAULT 0,     -- failed publish attempts so far
    next_attempt_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP, -- earliest time the relay may (re)try
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at    TIMESTAMP   NULL,                   -- NULL = not yet delivered to the broker
    PRIMARY KEY (id),
    KEY idx_outbox_unpublished (published_at, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
