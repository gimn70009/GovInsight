-- Apply once to an existing Oracle database before deploying Telegram management.
-- Bot tokens remain in server environment variables and are never stored here.
CREATE TABLE telegram_settings (
    settings_id NUMBER(19) PRIMARY KEY,
    version NUMBER(19),
    enabled NUMBER(1) NOT NULL,
    chat_id VARCHAR2(100 CHAR),
    recipient_name VARCHAR2(100 CHAR),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    CONSTRAINT ck_telegram_settings_singleton CHECK (settings_id = 1),
    CONSTRAINT ck_telegram_settings_enabled CHECK (enabled IN (0, 1))
);
-- No initial row: existing TELEGRAM_ENABLED and TELEGRAM_CHAT_ID remain the defaults
-- until the administrator saves settings through the page.
ALTER TABLE monitoring_reports ADD (
    telegram_chat_id VARCHAR2(100 CHAR),
    telegram_recipient_name VARCHAR2(100 CHAR),
    telegram_attempted_at TIMESTAMP,
    telegram_attempt_count NUMBER(10) DEFAULT 0 NOT NULL
);
-- Old deliveries intentionally retain NULL recipient snapshots; do not invent their targets.
