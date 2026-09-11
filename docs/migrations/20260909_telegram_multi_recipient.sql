-- Apply once after 20260909_telegram_management.sql. Existing data is retained.
CREATE TABLE telegram_recipients (
    settings_id NUMBER(19) NOT NULL,
    recipient_order NUMBER(10) NOT NULL,
    chat_id VARCHAR2(100 CHAR) NOT NULL,
    recipient_name VARCHAR2(100 CHAR),
    enabled NUMBER(1) NOT NULL,
    CONSTRAINT pk_telegram_recipients PRIMARY KEY (settings_id, recipient_order),
    CONSTRAINT fk_telegram_recipient_settings FOREIGN KEY (settings_id) REFERENCES telegram_settings(settings_id),
    CONSTRAINT uk_telegram_recipient_chat UNIQUE (settings_id, chat_id),
    CONSTRAINT ck_telegram_recipient_enabled CHECK (enabled IN (0, 1))
);
-- If settings were never saved, application environment defaults remain effective.
INSERT INTO telegram_recipients (settings_id, recipient_order, chat_id, recipient_name, enabled)
SELECT settings_id, 0, LOWER(TRIM(chat_id)), recipient_name, 1
FROM telegram_settings WHERE TRIM(chat_id) IS NOT NULL;

CREATE SEQUENCE telegram_deliveries_sequence START WITH 1 INCREMENT BY 1 NOCACHE;
CREATE TABLE telegram_deliveries (
    delivery_id NUMBER(19) PRIMARY KEY,
    report_id NUMBER(19) NOT NULL,
    chat_id VARCHAR2(100 CHAR) NOT NULL,
    recipient_name VARCHAR2(100 CHAR),
    status VARCHAR2(20 CHAR) NOT NULL,
    attempt_count NUMBER(10) NOT NULL,
    attempted_at TIMESTAMP,
    sent_at TIMESTAMP,
    message_id NUMBER(19),
    error_message VARCHAR2(2000 CHAR),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_telegram_delivery_report FOREIGN KEY (report_id) REFERENCES monitoring_reports(report_id),
    CONSTRAINT uk_telegram_delivery_target UNIQUE (report_id, chat_id),
    CONSTRAINT ck_telegram_delivery_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_telegram_delivery_attempt CHECK (attempt_count >= 0)
);
CREATE INDEX ix_telegram_delivery_status ON telegram_deliveries (report_id, status);
-- Migrate only recipients that were actually recorded, never infer past targets.
INSERT INTO telegram_deliveries (
    delivery_id, report_id, chat_id, recipient_name, status, attempt_count,
    attempted_at, sent_at, message_id, error_message, created_at, updated_at
)
SELECT telegram_deliveries_sequence.NEXTVAL, report_id, LOWER(TRIM(telegram_chat_id)),
       telegram_recipient_name,
       CASE WHEN telegram_sent_at IS NOT NULL THEN 'SENT' ELSE 'FAILED' END,
       telegram_attempt_count, telegram_attempted_at, telegram_sent_at, telegram_message_id,
       telegram_error_message, created_at, updated_at
FROM monitoring_reports
WHERE TRIM(telegram_chat_id) IS NOT NULL
  AND (telegram_sent_at IS NOT NULL OR telegram_error_message IS NOT NULL OR telegram_attempt_count > 0);
COMMIT;
