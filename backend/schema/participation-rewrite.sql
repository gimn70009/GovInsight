-- Oracle: additive cache fields for the participation-conditions section only.
-- Run once on existing databases before deploying the new backend (ddl-auto=none/validate).
ALTER TABLE document_versions ADD (
    participation_rewrite_key VARCHAR2(64 CHAR),
    participation_rewrite_json CLOB
);
