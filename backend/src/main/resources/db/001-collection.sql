--liquibase formatted sql
--changeset medcollect:001-mc_source
CREATE TABLE mc_source (
    id VARCHAR2(36) PRIMARY KEY,
    name VARCHAR2(120 CHAR) NOT NULL,
    url VARCHAR2(1000 CHAR) NOT NULL,
    note VARCHAR2(500 CHAR),
    enabled NUMBER(1) NOT NULL,
    created_at NUMBER(19) NOT NULL
);

--changeset medcollect:001-mc_task
CREATE TABLE mc_task (
    id VARCHAR2(36) PRIMARY KEY,
    name VARCHAR2(120 CHAR) NOT NULL,
    collection_mode VARCHAR2(20) NOT NULL,
    query_text VARCHAR2(1000 CHAR),
    source_id VARCHAR2(36) REFERENCES mc_source(id),
    capture_mode VARCHAR2(10) NOT NULL,
    result_limit NUMBER(3) NOT NULL,
    interval_minutes NUMBER(9) NOT NULL,
    next_run_at NUMBER(19),
    enabled NUMBER(1) NOT NULL,
    created_by VARCHAR2(80 CHAR) NOT NULL,
    created_at NUMBER(19) NOT NULL,
    last_run_at NUMBER(19),
    lease_token VARCHAR2(36),
    lease_until NUMBER(19),
    revision NUMBER(9) NOT NULL
);

--changeset medcollect:001-mc_task_schedule_ix
CREATE INDEX mc_task_schedule_ix ON mc_task(enabled, next_run_at);

--changeset medcollect:001-mc_run
CREATE TABLE mc_run (
    id VARCHAR2(36) PRIMARY KEY,
    task_id VARCHAR2(36) NOT NULL REFERENCES mc_task(id),
    status VARCHAR2(20) NOT NULL,
    actor VARCHAR2(80 CHAR) NOT NULL,
    started_at NUMBER(19) NOT NULL,
    finished_at NUMBER(19),
    new_count NUMBER(9) DEFAULT 0 NOT NULL,
    duplicate_count NUMBER(9) DEFAULT 0 NOT NULL,
    failure_count NUMBER(9) DEFAULT 0 NOT NULL,
    note VARCHAR2(1000 CHAR)
);

--changeset medcollect:001-mc_run_task_ix
CREATE INDEX mc_run_task_ix ON mc_run(task_id, started_at);

--changeset medcollect:001-mc_material
CREATE TABLE mc_material (
    id VARCHAR2(36) PRIMARY KEY,
    fingerprint VARCHAR2(64) NOT NULL UNIQUE,
    sha256 VARCHAR2(64) NOT NULL,
    title VARCHAR2(400 CHAR) NOT NULL,
    format_code VARCHAR2(12) NOT NULL,
    file_name VARCHAR2(240 CHAR) NOT NULL,
    mime_type VARCHAR2(120) NOT NULL,
    size_bytes NUMBER(19) NOT NULL,
    source_url VARCHAR2(1000 CHAR),
    fetched_url VARCHAR2(1000 CHAR),
    source_name VARCHAR2(160 CHAR),
    authors VARCHAR2(600 CHAR),
    publisher VARCHAR2(320 CHAR),
    published VARCHAR2(50 CHAR),
    external_ref VARCHAR2(320 CHAR),
    source_abstract CLOB,
    raw_text CLOB,
    status VARCHAR2(20) NOT NULL,
    captured_at NUMBER(19) NOT NULL,
    revision NUMBER(9) NOT NULL
);

--changeset medcollect:001-mc_material_review_ix
CREATE INDEX mc_material_review_ix ON mc_material(status, captured_at);

--changeset medcollect:001-mc_original
CREATE TABLE mc_original (
    material_id VARCHAR2(36) PRIMARY KEY REFERENCES mc_material(id),
    original_bytes BLOB NOT NULL
);

--changeset medcollect:001-mc_run_result
CREATE TABLE mc_run_result (
    id VARCHAR2(36) PRIMARY KEY,
    run_id VARCHAR2(36) NOT NULL REFERENCES mc_run(id),
    material_id VARCHAR2(36) REFERENCES mc_material(id),
    title VARCHAR2(400 CHAR),
    url VARCHAR2(1000 CHAR),
    status VARCHAR2(20) NOT NULL,
    note VARCHAR2(1000 CHAR)
);

--changeset medcollect:001-mc_run_result_run_ix
CREATE INDEX mc_run_result_run_ix ON mc_run_result(run_id);

--changeset medcollect:001-mc_run_result_material_ix
CREATE INDEX mc_run_result_material_ix ON mc_run_result(material_id);

--changeset medcollect:001-mc_knowledge
CREATE TABLE mc_knowledge (
    id VARCHAR2(36) PRIMARY KEY,
    material_id VARCHAR2(36) NOT NULL REFERENCES mc_material(id),
    title VARCHAR2(100 CHAR) NOT NULL,
    type_code VARCHAR2(20) NOT NULL,
    knowledge_class VARCHAR2(60 CHAR),
    summary CLOB,
    template_json CLOB NOT NULL,
    fields_json CLOB NOT NULL,
    relations_json CLOB NOT NULL,
    status VARCHAR2(20) NOT NULL,
    revision NUMBER(9) NOT NULL,
    model_name VARCHAR2(160 CHAR),
    created_at NUMBER(19) NOT NULL,
    updated_at NUMBER(19) NOT NULL,
    remote_id VARCHAR2(80),
    attachment_url VARCHAR2(1000 CHAR),
    sync_phase VARCHAR2(20),
    sync_lease NUMBER(19),
    sync_token VARCHAR2(36),
    sync_error VARCHAR2(1000 CHAR)
);

--changeset medcollect:001-mc_knowledge_material_ix
CREATE INDEX mc_knowledge_material_ix ON mc_knowledge(material_id);

--changeset medcollect:001-mc_knowledge_status_ix
CREATE INDEX mc_knowledge_status_ix ON mc_knowledge(status, updated_at);

--changeset medcollect:001-mc_event
CREATE TABLE mc_event (
    id VARCHAR2(36) PRIMARY KEY,
    entity_type VARCHAR2(20) NOT NULL,
    entity_id VARCHAR2(36) NOT NULL,
    action VARCHAR2(30) NOT NULL,
    actor VARCHAR2(80 CHAR) NOT NULL,
    occurred_at NUMBER(19) NOT NULL,
    note VARCHAR2(1000 CHAR)
);

--changeset medcollect:001-mc_event_entity_ix
CREATE INDEX mc_event_entity_ix ON mc_event(entity_type, entity_id, occurred_at);

--changeset medcollect:001-mc_settings
CREATE TABLE mc_settings (
    setting_key VARCHAR2(80) PRIMARY KEY,
    setting_value CLOB NOT NULL
);

