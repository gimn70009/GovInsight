-- Apply once to an existing Oracle database before using bookmarks.
CREATE SEQUENCE document_bookmarks_sequence START WITH 1 INCREMENT BY 1;
CREATE TABLE document_bookmarks (
    bookmark_id NUMBER(19) PRIMARY KEY,
    user_id NUMBER(19) NOT NULL,
    document_id NUMBER(19) NOT NULL,
    CONSTRAINT uk_bookmark_user_document UNIQUE (user_id, document_id),
    CONSTRAINT fk_bookmark_user FOREIGN KEY (user_id) REFERENCES app_users(user_id),
    CONSTRAINT fk_bookmark_document FOREIGN KEY (document_id) REFERENCES documents(document_id)
);
CREATE INDEX ix_bookmark_document ON document_bookmarks(document_id);
