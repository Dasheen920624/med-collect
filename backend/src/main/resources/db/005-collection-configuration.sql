--liquibase formatted sql
--changeset medcollect:005-classifications
CREATE TABLE mc_classification (
    id VARCHAR2(36) PRIMARY KEY,
    group_code VARCHAR2(12) NOT NULL,
    parent_id VARCHAR2(36) REFERENCES mc_classification(id),
    parent_key VARCHAR2(36) NOT NULL,
    name VARCHAR2(120 CHAR) NOT NULL,
    name_key VARCHAR2(240 CHAR) NOT NULL,
    business_code VARCHAR2(80 CHAR),
    display_order NUMBER(6) NOT NULL,
    enabled NUMBER(1) NOT NULL,
    revision NUMBER(9) NOT NULL,
    UNIQUE (group_code, parent_key, name_key)
);

--changeset medcollect:005-material-classifications
CREATE TABLE mc_material_class (
    material_id VARCHAR2(36) REFERENCES mc_material(id),
    class_id VARCHAR2(36) REFERENCES mc_classification(id),
    PRIMARY KEY (material_id, class_id)
);

--changeset medcollect:005-material-classification-ref
CREATE INDEX mc_material_class_ref_ix ON mc_material_class(class_id);

--changeset medcollect:005-classification-lock
INSERT INTO mc_settings (setting_key, setting_value) VALUES ('collection_classifications', '1');

--changeset medcollect:005-initialize-collection-classifications
INSERT INTO mc_classification VALUES ('material-guideline', 'MATERIAL', NULL, 'ROOT', '临床指南', '临床指南', NULL, 10, 1, 1);
INSERT INTO mc_classification VALUES ('material-pathway', 'MATERIAL', NULL, 'ROOT', '临床路径', '临床路径', NULL, 20, 1, 1);
INSERT INTO mc_classification VALUES ('material-consensus', 'MATERIAL', NULL, 'ROOT', '专家共识', '专家共识', NULL, 30, 1, 1);
INSERT INTO mc_classification VALUES ('material-book', 'MATERIAL', NULL, 'ROOT', '书籍', '书籍', NULL, 40, 1, 1);
INSERT INTO mc_classification VALUES ('material-journal', 'MATERIAL', NULL, 'ROOT', '期刊', '期刊', NULL, 50, 1, 1);
INSERT INTO mc_classification VALUES ('material-other', 'MATERIAL', NULL, 'ROOT', '其他资料', '其他资料', NULL, 60, 1, 1);
INSERT INTO mc_classification VALUES ('subject-cardiology', 'SUBJECT', NULL, 'ROOT', '心血管内科', '心血管内科', NULL, 10, 1, 1);
INSERT INTO mc_classification VALUES ('subject-hypertension', 'SUBJECT', 'subject-cardiology', 'subject-cardiology', '高血压', '高血压', '170103', 10, 1, 1);
INSERT INTO mc_classification VALUES ('domain-disease', 'DOMAIN', NULL, 'ROOT', '疾病', '疾病', '1', 10, 1, 1);
INSERT INTO mc_classification VALUES ('domain-lab', 'DOMAIN', NULL, 'ROOT', '检验', '检验', '8-1', 20, 1, 1);
INSERT INTO mc_classification VALUES ('domain-exam', 'DOMAIN', NULL, 'ROOT', '检查', '检查', '8-2', 30, 1, 1);
INSERT INTO mc_classification VALUES ('domain-drug', 'DOMAIN', NULL, 'ROOT', '药品', '药品', '6', 40, 1, 1);
INSERT INTO mc_classification VALUES ('domain-tcm', 'DOMAIN', NULL, 'ROOT', '中医', '中医', '24', 50, 1, 1);
INSERT INTO mc_classification VALUES ('domain-other', 'DOMAIN', NULL, 'ROOT', '其他', '其他', NULL, 60, 1, 1);

--changeset medcollect:005-template-profiles
CREATE TABLE mc_template_profile (
    profile_key VARCHAR2(128) PRIMARY KEY,
    rules_json CLOB NOT NULL,
    revision NUMBER(9) NOT NULL,
    updated_at NUMBER(19) NOT NULL
);
