--liquibase formatted sql
--changeset medcollect:011-parse-type
ALTER TABLE mc_parse_attempt ADD type_code VARCHAR2(20);
--changeset medcollect:011-parse-class
ALTER TABLE mc_parse_attempt ADD class_code VARCHAR2(60);
