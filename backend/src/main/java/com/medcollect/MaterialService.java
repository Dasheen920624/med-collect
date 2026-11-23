package com.medcollect;

import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class MaterialService {
    private final Store store;
    private final TransactionTemplate transactions;

    MaterialService(Store store, TransactionTemplate transactions) {
        this.store = store;
        this.transactions = transactions;
    }

    Registration register(Acquired input, String actor, String runId, String observation) {
        String sha256 = sha(input.bytes());
        String identity = trim(input.sourceUrl());
        String version = "METADATA".equals(input.formatCode())
                ? sha(trim(input.title()) + "\n" + trim(input.sourceAbstract()) + "\n" + trim(input.externalRef()))
                : sha256;
        String fingerprint = sha(input.formatCode() + "\n" + identity + "\n" + version);
        try {
            return transactions.execute(tx -> {
                var existing = store.existingMaterial(fingerprint);
                if (existing.isPresent()) return duplicate(input, runId, observation, existing.get());
                String id = id();
                MapSqlParameterSource vars = new MapSqlParameterSource()
                        .addValue("id", id).addValue("fingerprint", fingerprint).addValue("sha", sha256)
                        .addValue("title", limit(input.title(), 400)).addValue("format", input.formatCode())
                        .addValue("file", input.fileName()).addValue("mime", input.mimeType())
                        .addValue("bytes", input.bytes().length).addValue("captured", store.now())
                        .addValue("url", limit(input.sourceUrl(), 1000), Types.VARCHAR)
                        .addValue("fetched", limit(input.fetchedUrl(), 1000), Types.VARCHAR)
                        .addValue("source", limit(input.sourceName(), 160), Types.VARCHAR)
                        .addValue("authors", limit(input.authors(), 600), Types.VARCHAR)
                        .addValue("publisher", limit(input.publisher(), 320), Types.VARCHAR)
                        .addValue("published", limit(input.published(), 50), Types.VARCHAR)
                        .addValue("external", limit(input.externalRef(), 320), Types.VARCHAR)
                        .addValue("abstract", input.sourceAbstract(), Types.CLOB);
                store.db().sql("""
                        INSERT INTO mc_material (id, fingerprint, sha256, title, format_code, file_name, mime_type,
                          size_bytes, source_url, fetched_url, source_name, authors, publisher, published, external_ref,
                          source_abstract, status, captured_at, revision)
                        VALUES (:id, :fingerprint, :sha, :title, :format, :file, :mime, :bytes, :url, :fetched, :source,
                          :authors, :publisher, :published, :external, :abstract, 'PENDING', :captured, 1)
                        """).paramSource(vars).update();
                store.saveOriginal(id, input);
                store.event("MATERIAL", id, "CAPTURED", actor, input.sourceUrl());
                result(runId, id, input.title(), input.sourceUrl(),
                        "METADATA".equals(input.formatCode()) ? "METADATA" : "CAPTURED", observation);
                return new Registration(id, false);
            });
        } catch (DuplicateKeyException e) {
            return transactions.execute(tx -> duplicate(input, runId, observation,
                    store.existingMaterial(fingerprint).orElseThrow(() -> e)));
        }
    }

    private Registration duplicate(Acquired input, String runId, String observation, String materialId) {
        result(runId, materialId, input.title(), input.sourceUrl(), "DUPLICATE", observation);
        return new Registration(materialId, true);
    }

    void result(String runId, String materialId, String title, String url, String status, String note) {
        result(runId, materialId, title, url, status, note, null);
    }

    void result(String runId, String materialId, String title, String url, String status, String note, String sourceId) {
        if (runId == null) return;
        store.db().sql("""
                INSERT INTO mc_run_result (id, run_id, material_id, title, url, status, note, source_id)
                VALUES (:id, :run, :material, :title, :url, :status, :note, :source)
                """).paramSource(new MapSqlParameterSource()
                .addValue("id", id()).addValue("run", runId).addValue("material", materialId, Types.VARCHAR)
                .addValue("title", limit(title, 400), Types.VARCHAR).addValue("url", limit(url, 1000), Types.VARCHAR)
                .addValue("status", status).addValue("note", limit(note, 1000), Types.VARCHAR)
                .addValue("source", sourceId, Types.VARCHAR)).update();
    }

    Page<MaterialList> list(String query, MaterialStatus status, String format, String classId,
                            boolean originalsOnly, int page, int size) {
        var filters = new HashMap<String, Object>();
        String from = "FROM mc_material WHERE 1=1";
        if (!trim(query).isBlank()) {
            from += " AND (INSTR(LOWER(title), :query) > 0 OR INSTR(LOWER(source_name), :query) > 0"
                    + " OR INSTR(LOWER(authors), :query) > 0 OR INSTR(LOWER(publisher), :query) > 0"
                    + " OR INSTR(LOWER(external_ref), :query) > 0)";
            filters.put("query", query.strip().toLowerCase(Locale.ROOT));
        }
        if (status != null) {
            from += " AND status = :status";
            filters.put("status", status.name());
        }
        if (!trim(format).isBlank()) {
            from += " AND format_code = :format";
            filters.put("format", format);
        }
        if (originalsOnly) from += " AND format_code <> 'METADATA'";
        if ("unclassified".equals(classId)) {
            from += " AND NOT EXISTS (SELECT 1 FROM mc_material_class a WHERE a.material_id = mc_material.id)";
        } else if (!trim(classId).isBlank()) {
            from += " AND EXISTS (SELECT 1 FROM mc_material_class a JOIN mc_classification c ON c.id = a.class_id"
                    + " WHERE a.material_id = mc_material.id AND (a.class_id = :class OR c.parent_id = :class))";
            filters.put("class", classId);
        }
        return store.page("id, title, format_code, file_name, source_url, source_name, captured_at, status",
                from, "captured_at DESC, id", filters, page, size, MaterialList.class);
    }

    @Transactional
    MaterialDetail decide(String id, MaterialDecision decision, String actor) {
        Material material = store.material(id);
        if (material.status() == MaterialStatus.APPROVED) throw conflict("这份原文已审核通过");
        String note = decision.approved() ? trim(decision.note()) : requireText(decision.note(), "退回时请填写原因");
        MaterialStatus status = decision.approved() ? MaterialStatus.APPROVED : MaterialStatus.RETURNED;
        String summary = decision.sourceAbstract() == null ? material.sourceAbstract() : trim(decision.sourceAbstract());
        changed(store.db().sql("""
                UPDATE mc_material SET status = :next, source_abstract = :abstract, revision = revision + 1
                WHERE id = :id AND revision = :revision AND status IN ('PENDING', 'RETURNED')
                """).param("next", status.name()).param("abstract", summary, Types.CLOB)
                .param("id", id).param("revision", decision.revision()).update());
        store.event("MATERIAL", id, status.name(), actor, note);
        Material updated = store.material(id);
        return new MaterialDetail(updated, store.extractedText(updated));
    }

    List<Run> runs(String materialId) {
        return store.db().sql("""
                SELECT r.* FROM mc_run r WHERE EXISTS (
                  SELECT 1 FROM mc_run_result p WHERE p.run_id = r.id AND p.material_id = :id
                ) ORDER BY r.started_at DESC FETCH FIRST 100 ROWS ONLY
                """).param("id", materialId).query(Run.class).list();
    }
}
