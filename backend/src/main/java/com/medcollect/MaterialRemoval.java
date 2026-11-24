package com.medcollect;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import static com.medcollect.Support.*;

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

    void remove(String id,int revision,String reason,String actor) {
        String note=requireText(reason,"请填写移除原因");
        PendingFile file=transactions.execute(tx -> {
            if (store.db().sql("SELECT id FROM mc_material WHERE id=:id FOR UPDATE")
                    .param("id",id).query(String.class).optional().isEmpty()) {
                return store.db().sql("SELECT material_id,pending_path FROM mc_removed_material WHERE material_id=:id")
                        .param("id",id).query(PendingFile.class).optional().orElseThrow(() -> missing("原文资料不存在"));
            }
            var material=store.material(id);
            if (material.revision()!=revision) throw conflict("原文已变更，请刷新后核对");
            var db=store.db();
            List<String> drafts=db.sql("SELECT id FROM mc_knowledge WHERE material_id=:id ORDER BY id FOR UPDATE")
                    .param("id",id).query(String.class).list();
            for (String draft:drafts) {
                var k=store.knowledge(draft);
                if (k.remoteId()!=null || k.syncPhase()!=null || !List.of("DRAFT","RETURNED","PENDING","REFERENCE_ONLY").contains(k.status().name())
                        || db.sql("SELECT COUNT(*) FROM mc_sync_version WHERE knowledge_id=:id").param("id",draft).query(Long.class).single()>0
                        || db.sql("SELECT COUNT(*) FROM mc_event WHERE entity_type='KNOWLEDGE' AND entity_id=:id AND action IN ('APPROVED','SYNC_STARTED')")
                            .param("id",draft).query(Long.class).single()>0) throw conflict("已有审批或同步记录，请保留该原件及其版本");
            }
            if (!trim(material.sourceUrl()).isEmpty() && db.sql("""
                    SELECT COUNT(*) FROM mc_knowledge WHERE material_id<>:id AND status NOT IN ('DRAFT','RETURNED')
                    AND (INSTR(summary,:url)>0 OR INSTR(fields_json,:url)>0)
                    """).param("id",id).param("url",material.sourceUrl()).query(Long.class).single()>0) {
                throw conflict("其他知识稿仍引用这个原文地址，请先核对引用");
            }
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
