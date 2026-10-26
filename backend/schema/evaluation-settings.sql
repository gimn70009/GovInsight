-- Oracle: apply once when automatic schema updates are disabled.
-- Adds settings storage only; existing document and report data are untouched.
CREATE TABLE evaluation_settings (
    settings_id NUMBER(19, 0) NOT NULL,
    version NUMBER(19, 0),
    settings_data CLOB NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_evaluation_settings PRIMARY KEY (settings_id)
);

CREATE TABLE evaluation_settings_revisions (
    settings_revision NUMBER(19, 0) NOT NULL,
    settings_data CLOB NOT NULL,
    CONSTRAINT pk_evaluation_settings_rev PRIMARY KEY (settings_revision)
);
