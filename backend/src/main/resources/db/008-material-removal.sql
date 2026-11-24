--liquibase formatted sql
--changeset medcollect:008-material-removal
CREATE TABLE mc_removed_material (
    material_id VARCHAR2(36) PRIMARY KEY,
    fingerprint VARCHAR2(64) NOT NULL UNIQUE,
    pending_path VARCHAR2(180),
    removed_at NUMBER(19) NOT NULL
);
