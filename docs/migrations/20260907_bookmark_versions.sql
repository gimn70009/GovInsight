-- Apply once after 20260907_document_bookmarks.sql with the backend stopped.
-- Legacy bookmarks did not record the chosen version: preserve the version
-- that the saved list displayed immediately before migration.
ALTER TABLE document_bookmarks ADD version_id NUMBER(19);
UPDATE document_bookmarks b SET version_id = (
 SELECT MAX(d.version_id) KEEP (DENSE_RANK LAST ORDER BY d.detected_at, d.detection_id)
 FROM document_detections d WHERE d.document_id = b.document_id
);
ALTER TABLE document_bookmarks MODIFY version_id NOT NULL;
ALTER TABLE document_bookmarks ADD CONSTRAINT fk_bookmark_version FOREIGN KEY (version_id) REFERENCES document_versions(version_id);
ALTER TABLE document_bookmarks ADD CONSTRAINT uk_bookmark_user_version UNIQUE (user_id, version_id);
ALTER TABLE document_bookmarks DROP CONSTRAINT uk_bookmark_user_document;
-- Preserve the original document mapping for audit; new rows only use version_id.
ALTER TABLE document_bookmarks MODIFY document_id NULL;
CREATE INDEX ix_bookmark_version ON document_bookmarks(version_id);
