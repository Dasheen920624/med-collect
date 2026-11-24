package com.medcollect;

import org.springframework.stereotype.Component;

@Component
class SyncOrigins {
    static final String PROTECTED = "4.23 原有知识缺少采集系统的新增回执，仅供对照";
    private final Store store;

    SyncOrigins(Store store) { this.store = store; }

    boolean owns(String remoteId) {
        return remoteId != null && store.db().sql("SELECT COUNT(*) FROM mc_sync_origin WHERE remote_id = :remote")
                .param("remote", remoteId).query(Integer.class).single() == 1;
    }

    boolean createdBy(String remoteId, String knowledgeId) {
        return remoteId != null && store.db().sql("SELECT COUNT(*) FROM mc_sync_origin WHERE remote_id = :remote AND knowledge_id = :id")
                .param("remote", remoteId).param("id", knowledgeId).query(Integer.class).single() == 1;
    }

    void requireOwned(String remoteId) {
        if (!owns(remoteId)) throw Support.conflict(PROTECTED);
    }

    // Called in the same transaction as the confirmed CREATE checkpoint.
    void recordCreation(String remoteId, String knowledgeId) {
        store.db().sql("INSERT INTO mc_sync_origin (remote_id, knowledge_id, created_at) VALUES (:remote, :id, :now)")
                .param("remote", remoteId).param("id", knowledgeId).param("now", store.now()).update();
    }
}
