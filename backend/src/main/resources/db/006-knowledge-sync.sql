--liquibase formatted sql
--changeset medcollect:006-knowledge-revision-target
ALTER TABLE mc_knowledge ADD update_target_id VARCHAR2(80);

--changeset medcollect:006-sync-review
CREATE TABLE mc_sync_review (
    knowledge_id VARCHAR2(36) PRIMARY KEY REFERENCES mc_knowledge(id),
    review_id VARCHAR2(36) NOT NULL UNIQUE,
    draft_revision NUMBER(9) NOT NULL,
    plan_json CLOB NOT NULL,
    reviewer VARCHAR2(80 CHAR),
    approved_at NUMBER(19)
);

--changeset medcollect:006-sync-versions
CREATE TABLE mc_sync_version (
    id VARCHAR2(36) PRIMARY KEY,
    knowledge_id VARCHAR2(36) NOT NULL REFERENCES mc_knowledge(id),
    remote_id VARCHAR2(80) NOT NULL,
    version_no NUMBER(9) NOT NULL,
    operation VARCHAR2(12) NOT NULL,
    before_json CLOB,
    after_json CLOB NOT NULL,
    changes_json CLOB NOT NULL,
    reviewer VARCHAR2(80 CHAR) NOT NULL,
    approved_at NUMBER(19) NOT NULL,
    synced_at NUMBER(19) NOT NULL,
    UNIQUE (remote_id, version_no)
);

--changeset medcollect:006-sync-writer-lock
CREATE TABLE mc_sync_writer (
    id NUMBER(1) PRIMARY KEY,
    lease_token VARCHAR2(36),
    lease_until NUMBER(19)
);

--changeset medcollect:006-sync-writer-initialization
INSERT INTO mc_sync_writer (id) VALUES (1);
