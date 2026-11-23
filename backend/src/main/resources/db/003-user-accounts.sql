--liquibase formatted sql
--changeset medcollect:003-mc_user_account
CREATE TABLE mc_user_account (
    id VARCHAR2(36) PRIMARY KEY,
    username VARCHAR2(80 CHAR) NOT NULL,
    username_key VARCHAR2(240 CHAR) NOT NULL UNIQUE,
    password_hash VARCHAR2(100) NOT NULL,
    role_code VARCHAR2(12) NOT NULL CHECK (role_code IN ('ADMIN', 'REVIEWER')),
    enabled NUMBER(1) NOT NULL CHECK (enabled IN (0, 1)),
    revision NUMBER(9) NOT NULL,
    created_at NUMBER(19) NOT NULL,
    updated_at NUMBER(19) NOT NULL
);

--changeset medcollect:003-user-accounts-initialization
INSERT INTO mc_settings (setting_key, setting_value) VALUES ('user_accounts_initialized', 'false');
