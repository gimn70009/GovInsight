-- Additive migration for existing Oracle databases using ddl-auto=none/validate.
ALTER TABLE document_versions ADD (
    submission_notes_key VARCHAR2(64 CHAR),
    submission_notes_json CLOB
);
