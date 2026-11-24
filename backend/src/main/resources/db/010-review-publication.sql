--liquibase formatted sql
--changeset medcollect:010-review-publication
ALTER TABLE mc_knowledge ADD business_release VARCHAR2(8);

--changeset medcollect:010-parse-attempt
CREATE TABLE mc_parse_attempt (
 id VARCHAR2(36) PRIMARY KEY, material_id VARCHAR2(36) NOT NULL REFERENCES mc_material(id) ON DELETE CASCADE,
 state VARCHAR2(16) NOT NULL, started_at NUMBER(19) NOT NULL, finished_at NUMBER(19)
);
--changeset medcollect:010-parse-lookup
CREATE INDEX mc_parse_material_time ON mc_parse_attempt(material_id, started_at);

--changeset medcollect:010-task-material-types
CREATE TABLE mc_task_material_type (
 task_id VARCHAR2(36) NOT NULL REFERENCES mc_task(id) ON DELETE CASCADE,
 class_id VARCHAR2(36) NOT NULL REFERENCES mc_classification(id),
 PRIMARY KEY(task_id,class_id)
);
