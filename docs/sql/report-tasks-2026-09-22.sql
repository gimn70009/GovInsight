-- Oracle: apply once before deploying when JPA_DDL_AUTO is validate/none.
-- With the local default JPA_DDL_AUTO=update, Hibernate creates this table and sequence.
CREATE SEQUENCE report_tasks_sequence START WITH 1 INCREMENT BY 1;
CREATE TABLE report_tasks (
    task_id NUMBER(19) NOT NULL PRIMARY KEY,
    report_id NUMBER(19) NOT NULL,
    state VARCHAR2(24 CHAR) NOT NULL,
    request_json CLOB,
    attempt_count NUMBER(10) NOT NULL,
    attempt_id VARCHAR2(36 CHAR),
    available_at TIMESTAMP NOT NULL,
    last_error VARCHAR2(500 CHAR),
    CONSTRAINT uk_report_tasks_report UNIQUE (report_id),
    CONSTRAINT fk_report_tasks_report FOREIGN KEY (report_id) REFERENCES monitoring_reports(report_id)
);
CREATE INDEX ix_report_tasks_due ON report_tasks(state, available_at);
