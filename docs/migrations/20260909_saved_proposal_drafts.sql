-- Apply once to an existing Oracle database. Existing rows are not changed.
CREATE SEQUENCE document_proposal_drafts_seq START WITH 1 INCREMENT BY 1;
CREATE TABLE document_proposal_drafts (
    draft_id NUMBER(19) PRIMARY KEY,
    user_id NUMBER(19) NOT NULL,
    attachment_id NUMBER(19) NOT NULL,
    part_index NUMBER(10) NOT NULL,
    result_json CLOB NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    last_viewed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_proposal_draft_source UNIQUE (user_id, attachment_id, part_index),
    CONSTRAINT fk_proposal_draft_user FOREIGN KEY (user_id) REFERENCES app_users(user_id),
    CONSTRAINT fk_proposal_draft_attachment FOREIGN KEY (attachment_id) REFERENCES document_attachments(attachment_id)
);
CREATE INDEX ix_proposal_draft_attachment ON document_proposal_drafts(attachment_id);
