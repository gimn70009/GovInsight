-- Additive migration. Existing draft content and timestamps remain unchanged.
ALTER TABLE document_proposal_drafts ADD (
    previous_result_json CLOB,
    revision NUMBER(19) DEFAULT 0 NOT NULL,
    last_operation_id VARCHAR2(36)
);
