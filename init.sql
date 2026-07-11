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
