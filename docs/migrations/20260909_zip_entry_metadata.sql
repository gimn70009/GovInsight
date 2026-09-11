-- 기존 첨부·분석·초안 데이터를 보존하며 한 번 적용합니다.
ALTER TABLE document_attachments ADD (archive_entries_json CLOB);
