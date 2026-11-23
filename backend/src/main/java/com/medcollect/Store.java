package com.medcollect;

import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.io.IOException;
import java.sql.Types;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

@Repository
class Store {
    private static final Logger log = LoggerFactory.getLogger(Store.class);
    private final Clock clock;
    private final JdbcClient db;
    private final OriginalFiles originals;

    Store(Clock clock, JdbcClient db, OriginalFiles originals) {
        this.clock = clock;
        this.db = db;
        this.originals = originals;
    }

    long now() { return clock.millis(); }
    JdbcClient db() { return db; }

    Source source(String id) {
        return find("mc_source", id, Source.class, "采集来源不存在");
    }

    Task task(String id) {
        return find("mc_task", id, Task.class, "采集任务不存在");
    }

    Run run(String id) {
        return find("mc_run", id, Run.class, "执行记录不存在");
    }

    Material material(String id) {
        return find("mc_material", id, Material.class, "原文资料不存在");
    }

    Knowledge knowledge(String id) {
        return find("mc_knowledge", id, Knowledge.class, "知识稿件不存在");
    }

    private <T> T find(String table, String id, Class<T> type, String error) {
        return db.sql("SELECT * FROM " + table + " WHERE id = :id").param("id", id)
                .query(type).optional().orElseThrow(() -> missing(error));
    }

    Optional<String> existingMaterial(String fingerprint) {
        return db.sql("SELECT id FROM mc_material WHERE fingerprint = :fingerprint")
                .param("fingerprint", fingerprint).query(String.class).optional();
    }

    void saveOriginal(String materialId, Acquired input) {
        String path;
        try {
            path = originals.write(materialId, input.formatCode(), input.bytes(),
                    "PDF".equals(input.formatCode()) ? null : input.rawText());
        } catch (IOException e) {
            log.error("Could not store original for material {}", materialId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "原件存储暂不可用", e);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                try {
                    originals.discard(path);
                } catch (IOException e) {
                    log.warn("Could not remove files for rolled-back material {}", materialId, e);
                }
            }
        });
        db.sql("INSERT INTO mc_original (material_id, original_path) VALUES (:id, :path)")
                .param("id", materialId).param("path", path).update();
    }

    byte[] bytes(Material material) {
        try {
            byte[] bytes = originals.read(originalPath(material.id()));
            if (bytes.length != material.sizeBytes() || !sha(bytes).equalsIgnoreCase(material.sha256())) {
                throw new IOException("Original checksum or size differs from the material record");
            }
            return bytes;
        } catch (IOException e) {
            log.error("Could not read original for material {}", material.id(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "原件文件暂不可读取", e);
        }
    }

    String extractedText(Material material) {
        if (material.pdf()) return null;
        try {
            return originals.text(originalPath(material.id()));
        } catch (IOException e) {
            log.error("Could not read extracted text for material {}", material.id(), e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "原件提取文本暂不可读取", e);
        }
    }

    private String originalPath(String materialId) {
        return db.sql("SELECT original_path FROM mc_original WHERE material_id = :id")
                .param("id", materialId).query(String.class)
                .optional().orElseThrow(() -> missing("原件不存在"));
    }

    void event(String entity, String entityId, String action, String actor, String note) {
        db.sql("""
                INSERT INTO mc_event (id, entity_type, entity_id, action, actor, occurred_at, note)
                VALUES (:id, :type, :entity, :action, :actor, :now, :note)
                """).paramSource(new MapSqlParameterSource()
                .addValue("id", id()).addValue("type", entity).addValue("entity", entityId)
                .addValue("action", action).addValue("actor", actor).addValue("now", now())
                .addValue("note", limit(note, 1000), Types.VARCHAR)).update();
    }

    List<Event> events(String entity, String id) {
        return db.sql("""
                SELECT * FROM mc_event WHERE entity_type = :type AND entity_id = :id
                ORDER BY occurred_at DESC, id DESC
                """).params(Map.of("type", entity, "id", id)).query(Event.class).list();
    }

    <T> Page<T> page(String columns, String from, String order, Map<String, ?> filters,
                     int page, int size, Class<T> rowType) {
        int current = Math.clamp(page, 1, 10_000);
        int limit = Math.clamp(size, 1, 100);
        long total = db.sql("SELECT COUNT(*) " + from).params(filters).query(Long.class).single();
        List<T> rows = db.sql("SELECT " + columns + " " + from + " ORDER BY " + order
                        + " OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY")
                .params(filters).param("offset", (long) (current - 1) * limit).param("limit", limit)
                .query(rowType).list();
        return new Page<>(rows, total, current, limit);
    }

    Dashboard dashboard(String subjectId) {
        var params = new java.util.HashMap<String, Object>();
        String materials = "mc_material m WHERE 1=1" + Classifications.subjectScope("m.id", subjectId, params);
        String knowledge = "mc_knowledge k WHERE 1=1" + Classifications.subjectScope("k.material_id", subjectId, params);
        String tasks = "mc_task t WHERE 1=1" + Classifications.taskScope(subjectId, params);
        return new Dashboard(count(tasks, params), count(materials + " AND format_code <> 'METADATA'", params),
                count(materials + " AND status = 'PENDING'", params), count(knowledge + " AND status = 'PENDING'", params),
                count(knowledge + " AND status = 'SYNCED'", params), count(materials + " AND format_code = 'METADATA'", params),
                count(materials + " AND format_code <> 'METADATA' AND NOT EXISTS"
                        + " (SELECT 1 FROM mc_knowledge k WHERE k.material_id = m.id)", params),
                count(knowledge + " AND k.status = 'APPROVED'", params),count(knowledge + " AND k.status = 'SYNC_FAILED'", params),
                count(knowledge + " AND k.status = 'SYNCED' AND (k.sync_phase IS NULL OR k.sync_phase <> 'SKIPPED')", params),
                count(knowledge + " AND k.status = 'SYNCED' AND k.sync_phase = 'SKIPPED'", params));
    }

    private long count(String from, Map<String, Object> params) {
        return db.sql("SELECT COUNT(*) FROM " + from).params(params).query(Long.class).single();
    }
}
