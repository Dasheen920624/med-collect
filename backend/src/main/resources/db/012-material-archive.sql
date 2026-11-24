--liquibase formatted sql
--changeset medcollect:012-archive-time
ALTER TABLE mc_material ADD archived_at NUMBER(19);
--changeset medcollect:012-archive-actor
ALTER TABLE mc_material ADD archived_by VARCHAR2(160);
--changeset medcollect:012-archive-reason
ALTER TABLE mc_material ADD archive_reason VARCHAR2(1600);
