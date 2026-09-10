-- Preserve existing execution counts and results; old rows retain NULL details.
ALTER TABLE monitoring_run_sources ADD (warning_details_json CLOB);
