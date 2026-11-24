-- Oracle: preserve existing documents and paid results; add persisted display data.
CREATE TABLE document_detail_snapshots (
    version_id NUMBER(19) PRIMARY KEY REFERENCES document_versions(version_id) ON DELETE CASCADE,
    revision NUMBER(19) DEFAULT 0 NOT NULL,
    state VARCHAR2(16 CHAR) DEFAULT 'QUEUED' NOT NULL,
    policy_version VARCHAR2(60 CHAR),
    payload_json CLOB,
    attempt_count NUMBER(10) DEFAULT 0 NOT NULL,
    attempt_id VARCHAR2(36 CHAR),
    available_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_detail_snapshot_state CHECK (state IN ('QUEUED','BUILDING','READY','FAILED'))
);
CREATE INDEX ix_detail_snapshot_due ON document_detail_snapshots(state, available_at);
