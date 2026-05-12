CREATE TABLE revenue_ledger (
    id          BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    session_id  BIGINT         NOT NULL UNIQUE,
    sector      VARCHAR(32)    NOT NULL,
    amount      DECIMAL(10, 2) NOT NULL,
    earned_at   TIMESTAMP(3)   NOT NULL,
    created_at  TIMESTAMP(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT fk_ledger_session FOREIGN KEY (session_id) REFERENCES parking_sessions (id),
    CONSTRAINT fk_ledger_sector  FOREIGN KEY (sector)     REFERENCES sectors (name)
) ENGINE = InnoDB;

CREATE INDEX idx_ledger_revenue ON revenue_ledger (sector, earned_at);

ALTER TABLE parking_sessions
    DROP COLUMN amount_charged;
