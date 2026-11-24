package com.medcollect;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.medcollect.Support.*;
import static com.medcollect.Records.*;

@Service
class MaterialRemoval {
    private static final Logger log = LoggerFactory.getLogger(MaterialRemoval.class);
    private final Store store;
    private final OriginalFiles files;
    private final TransactionTemplate transactions;

    MaterialRemoval(Store store, OriginalFiles files, TransactionTemplate transactions) {
        this.store=store;
        this.files=files;
        this.transactions=transactions;
    }

    record PendingFile(String materialId,String pendingPath) {}
    record Options(boolean archive, boolean purge) {}

    Options options(Material material) {
        if (material.archivedAt() != null) return new Options(false,false);
        return new Options(!syncing(material.id()), !hasRetainedHistory(material.id()) && !externallyReferenced(material));
    }

    private boolean syncing(String id) {
        return store.db().sql("SELECT COUNT(*) FROM mc_knowledge WHERE material_id=:id AND status='SYNCING'")
                .param("id",id).query(Long.class).single() > 0;
    }

    private boolean hasRetainedHistory(String id) {
        return store.db().sql("""
                SELECT COUNT(*) FROM mc_knowledge k WHERE k.material_id=:id AND
                (k.remote_id IS NOT NULL OR k.sync_phase IS NOT NULL
                 OR k.status NOT IN ('DRAFT','RETURNED','PENDING','REFERENCE_ONLY','SYNC_DECLINED')
                 OR EXISTS (SELECT 1 FROM mc_sync_version v WHERE v.knowledge_id=k.id)
                 OR EXISTS (SELECT 1 FROM mc_event e WHERE e.entity_type='KNOWLEDGE' AND e.entity_id=k.id
                            AND e.action IN ('APPROVED','SYNC_STARTED')))
                """).param("id",id).query(Long.class).single() > 0;
    }

    private boolean externallyReferenced(Material material) {
        return !trim(material.sourceUrl()).isEmpty() && store.db().sql("""
                SELECT COUNT(*) FROM mc_knowledge WHERE material_id<>:id AND status NOT IN ('DRAFT','RETURNED')
                AND (INSTR(summary,:url)>0 OR INSTR(fields_json,:url)>0)
                """).param("id",material.id()).param("url",material.sourceUrl()).query(Long.class).single() > 0;
    }

    void archive(String id, int revision, String reason, String actor) {
        String note = requireText(reason, "请填写移除原因");
        transactions.executeWithoutResult(tx -> {
            store.db().sql("SELECT id FROM mc_material WHERE id=:id FOR UPDATE").param("id",id)
                    .query(String.class).optional().orElseThrow(() -> missing("原文资料不存在"));
            var material = store.material(id);
            if (material.archivedAt() != null) return;
            if (material.revision() != revision) throw conflict("原文已变更，请刷新后核对");
            if (syncing(id)) throw conflict("关联稿件正在同步，请核对同步回执后再移除");
            changed(store.db().sql("""
                    UPDATE mc_material SET archived_at=:now, archived_by=:actor, archive_reason=:note, revision=revision+1
                    WHERE id=:id AND revision=:revision AND archived_at IS NULL
                    """).param("id",id).param("revision",revision).param("now",store.now()).param("actor",actor).param("note",note).update());
            store.event("MATERIAL",id,"ARCHIVED",actor,note);
        });
    }

    void remove(String id,int revision,String reason,String actor) {
        String note=requireText(reason,"请填写移除原因");
        PendingFile file=transactions.execute(tx -> {
            if (store.db().sql("SELECT id FROM mc_material WHERE id=:id FOR UPDATE")
                    .param("id",id).query(String.class).optional().isEmpty()) {
                return store.db().sql("SELECT material_id,pending_path FROM mc_removed_material WHERE material_id=:id")
                        .param("id",id).query(PendingFile.class).optional().orElseThrow(() -> missing("原文资料不存在"));
            }
            var material=store.requireActive(store.material(id));
            if (material.revision()!=revision) throw conflict("原文已变更，请刷新后核对");
            var db=store.db();
            List<String> drafts=db.sql("SELECT id FROM mc_knowledge WHERE material_id=:id ORDER BY id FOR UPDATE")
                    .param("id",id).query(String.class).list();
            if (hasRetainedHistory(id)) throw conflict("已有审批或同步记录，可移出工作台并保留历史");
            if (externallyReferenced(material)) throw conflict("其他知识稿仍引用这个原文，可移出工作台并保留原件");
            String path=db.sql("SELECT original_path FROM mc_original WHERE material_id=:id")
                    .param("id",id).query(String.class).single();
            db.sql("INSERT INTO mc_removed_material(material_id,fingerprint,pending_path,removed_at) VALUES(:id,:fp,:path,:now)")
                    .param("id",id).param("fp",material.fingerprint()).param("path",path).param("now",store.now()).update();
            if (!drafts.isEmpty()) {
                db.sql("DELETE FROM mc_sync_review WHERE knowledge_id IN (:ids)").param("ids",drafts).update();
                db.sql("DELETE FROM mc_event WHERE entity_type='KNOWLEDGE' AND entity_id IN (:ids)").param("ids",drafts).update();
                db.sql("DELETE FROM mc_knowledge WHERE material_id=:id").param("id",id).update();
            }
            db.sql("UPDATE mc_run_result SET material_id=NULL,title='已移除原件',url=NULL,status='REMOVED',note=NULL WHERE material_id=:id")
                    .param("id",id).update();
            db.sql("DELETE FROM mc_material_class WHERE material_id=:id").param("id",id).update();
            db.sql("DELETE FROM mc_original WHERE material_id=:id").param("id",id).update();
            db.sql("DELETE FROM mc_event WHERE entity_type='MATERIAL' AND entity_id=:id").param("id",id).update();
            db.sql("DELETE FROM mc_material WHERE id=:id").param("id",id).update();
            store.event("MATERIAL",id,"REMOVED",actor,note);
            return new PendingFile(id,path);
        });
        clearFile(file);
    }

    @Scheduled(initialDelay=15000, fixedDelay=60000)
    void retryFiles() {
        store.db().sql("SELECT material_id,pending_path FROM mc_removed_material WHERE pending_path IS NOT NULL ORDER BY removed_at FETCH FIRST 100 ROWS ONLY")
                .query(PendingFile.class).list().forEach(this::clearFile);
    }

    private void clearFile(PendingFile file) {
        if (file.pendingPath()==null) return;
        try {
            files.discard(file.pendingPath());
            store.db().sql("UPDATE mc_removed_material SET pending_path=NULL WHERE material_id=:id AND pending_path=:path")
                    .param("id",file.materialId()).param("path",file.pendingPath()).update();
        } catch (IOException e) {
            log.warn("Will retry original removal for {}",file.materialId(),e);
        }
    }
}
