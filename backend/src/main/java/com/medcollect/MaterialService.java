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
        return register(input,actor,runId,observation,null);
    }

    Registration register(Acquired input, String actor, String runId, String observation, String selectedType) {
        String sha256 = sha(input.bytes());
        String identity = trim(input.sourceUrl());
        boolean business = "4.23 知识管理".equals(input.sourceName()) && trim(input.externalRef()).startsWith("CDSS:");
        String version = business ? sha(sha256+"\n"+input.externalRef()+"\n"+input.title()+"\n"+trim(input.sourceAbstract()))
                : "METADATA".equals(input.formatCode())
                ? sha(trim(input.title()) + "\n" + trim(input.sourceAbstract()) + "\n" + trim(input.externalRef()))
                : sha256;
        String fingerprint = sha(input.formatCode() + "\n" + identity + "\n" + version);
        try {
            return transactions.execute(tx -> {
                requireAvailable(fingerprint);
                if(business && !"METADATA".equals(input.formatCode())) {
                    String[] link=input.externalRef().split(" ",2)[0].split(":",-1);
                    if(link.length==4) {
                        var matches=store.db().sql("""
                                SELECT m.id, k.title, k.summary FROM mc_knowledge k JOIN mc_material m ON m.id=k.material_id
                                WHERE k.remote_id=:remote AND k.status='SYNCED' AND m.sha256=:sha AND k.type_code=:kind
                                AND (k.knowledge_class=:classCode OR (k.knowledge_class IS NULL AND :classCode IS NULL))
                                ORDER BY k.updated_at DESC
                                """).param("remote",link[2]).param("sha",sha256).param("kind",link[1])
                                .param("classCode",link[3],Types.VARCHAR).query(ReviewedSource.class).list();
                        for(var known:matches) if(known.title().equals(input.title()) && normalized(known.summary()).equals(normalized(input.sourceAbstract())))
                            return duplicate(input,runId,observation,known.id());
                    }
                }
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
                requireAvailable(fingerprint);
                store.saveOriginal(id, input);
                if (business) {
                    String[] source=input.externalRef().split(" ",2)[0].split(":",-1);
                    if (source.length==4) store.db().sql("""
                            INSERT INTO mc_material_class (material_id,class_id)
                            SELECT DISTINCT :id,c.id FROM mc_classification c WHERE c.enabled=1 AND
                            (c.remote_key IN (:domain,:medium,:subject) OR c.id IN
                                (SELECT parent_id FROM mc_classification WHERE remote_key=:subject AND enabled=1))
                            """).param("id",id).param("domain","DOMAIN:"+source[1]).param("medium","MATERIAL:"+source[1])
                            .param("subject","SUBJECT:"+source[3]).update();
                }
                if (!business) {
                    String type = trim(selectedType).isBlank() ? inferredType(input.title(),input.externalRef(),input.sourceUrl()) : selectedType;
                    int assigned = store.db().sql("INSERT INTO mc_material_class (material_id,class_id) SELECT :id,id FROM mc_classification WHERE id=:type AND group_code='MATERIAL' AND enabled=1")
                            .param("id",id).param("type",type).update();
                    if (assigned==0 && !trim(selectedType).isBlank()) throw bad("所选资料类型已停用");
                }
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

    private record ReviewedSource(String id,String title,String summary) {}

    static String inferredType(String title,String ref,String url) {
        String name=trim(title).toLowerCase(Locale.ROOT);
        if (name.contains("共识") || name.contains("consensus")) return "material-consensus";
        if (name.contains("临床路径") || name.contains("clinical pathway")) return "material-pathway";
        if (name.contains("指南") || name.contains("guideline")) return "material-guideline";
        if (name.contains("教材") || name.contains("书籍") || name.contains("textbook") || trim(ref).startsWith("ISBN")) return "material-book";
        if (name.contains("期刊") || !BusinessKnowledge.firstDoi(ref,url).isBlank() || trim(url).startsWith("https://europepmc.org/article/")) return "material-journal";
        return "material-other";
    }

    private void requireAvailable(String fingerprint) {
        if (store.db().sql("SELECT material_id FROM mc_removed_material WHERE fingerprint=:fp")
                .param("fp",fingerprint).query(String.class).optional().isPresent()) {
            throw conflict("这份原件已移除");
        }
    }
    private String normalized(String value) { return trim(value).replaceAll("\\s+"," "); }

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

    record LibraryItem(String id, String title, String formatCode, String fileName, String sourceUrl, String sourceName,
                       long capturedAt, MaterialStatus status, String acquireMode, int knowledgeCount, Long lastParsedAt, String parseState) {}
    record LibraryRow(@com.fasterxml.jackson.annotation.JsonUnwrapped LibraryItem material, List<String> classIds, List<String> generatedTypes) {}
    private record Assignment(String materialId, String classId) {}

    Page<LibraryRow> list(String query, MaterialStatus status, String format, String classId, boolean originalsOnly,
                          String subjectId, String domainId, String materialTypeId, Mode acquireMode, Boolean parsed, String parseState,
                          Long fromTime, Long untilTime, int page, int size) {
        var filters = new HashMap<String, Object>();
        filters.put("activeSince", store.now() - java.time.Duration.ofMinutes(30).toMillis());
        String from = """
                FROM (SELECT s.*,
                    CASE WHEN active_count > 0 THEN 'RUNNING' WHEN last_state IN ('FAILED','RUNNING') THEN 'FAILED'
                      WHEN status='RETURNED' THEN 'CHECK' WHEN knowledge_count>0 THEN 'COMPLETED' ELSE 'UNPARSED' END parse_state,
                    CASE WHEN last_attempt_at IS NULL OR last_attempt_at < generated_at THEN generated_at ELSE last_attempt_at END last_parsed_at
                  FROM (SELECT m.id, m.title, m.format_code, m.file_name, m.source_url, m.source_name, m.captured_at,
                    m.status, m.authors, m.publisher, m.external_ref,
                    COALESCE((SELECT t.collection_mode FROM mc_run_result p JOIN mc_run r ON r.id = p.run_id
                      JOIN mc_task t ON t.id = r.task_id WHERE p.material_id = m.id AND p.status IN ('CAPTURED','METADATA')
                      ORDER BY r.started_at, r.id FETCH FIRST 1 ROW ONLY),
                      CASE WHEN m.source_name = '手工上传' THEN 'UPLOAD' END) acquire_mode,
                    (SELECT COUNT(*) FROM mc_knowledge k WHERE k.material_id = m.id) knowledge_count,
                    (SELECT MAX(k.created_at) FROM mc_knowledge k WHERE k.material_id = m.id) generated_at,
                    (SELECT COUNT(*) FROM mc_parse_attempt p WHERE p.material_id=m.id AND p.state='RUNNING' AND p.started_at>:activeSince) active_count,
                    (SELECT MAX(COALESCE(p.finished_at,p.started_at)) FROM mc_parse_attempt p WHERE p.material_id=m.id) last_attempt_at,
                    (SELECT p.state FROM mc_parse_attempt p WHERE p.material_id=m.id ORDER BY COALESCE(p.finished_at,p.started_at) DESC, p.id DESC FETCH FIRST 1 ROW ONLY) last_state
                  FROM mc_material m) s) m WHERE 1=1
                """;
        if (!trim(query).isBlank()) {
            from += " AND (INSTR(LOWER(title), :query) > 0 OR INSTR(LOWER(source_name), :query) > 0"
                    + " OR INSTR(LOWER(authors), :query) > 0 OR INSTR(LOWER(publisher), :query) > 0"
                    + " OR INSTR(LOWER(external_ref), :query) > 0)";
            filters.put("query", query.strip().toLowerCase(Locale.ROOT));
        }
        if (status != null) { from += " AND status = :status"; filters.put("status", status.name()); }
        if (!trim(format).isBlank()) { from += " AND format_code = :format"; filters.put("format", format); }
        if (acquireMode != null) { from += " AND acquire_mode = :mode"; filters.put("mode", acquireMode.name()); }
        if (fromTime != null) { from += " AND captured_at >= :fromTime"; filters.put("fromTime", fromTime); }
        if (untilTime != null) { from += " AND captured_at < :untilTime"; filters.put("untilTime", untilTime); }
        if (parsed != null) from += parsed ? " AND knowledge_count > 0" : " AND knowledge_count = 0";
        if (!trim(parseState).isBlank()) { from += " AND parse_state = :parseState"; filters.put("parseState",parseState); }
        if (originalsOnly) from += " AND format_code <> 'METADATA'";
        from += Classifications.subjectScope("m.id", subjectId, filters);
        if ("unlinked".equals(classId)) {
            from += " AND EXISTS (SELECT 1 FROM mc_material_class a JOIN mc_classification c ON c.id=a.class_id WHERE a.material_id=m.id AND c.group_code='SUBJECT')"
                    + " AND NOT EXISTS (SELECT 1 FROM mc_material_class a JOIN mc_classification c ON c.id=a.class_id WHERE a.material_id=m.id AND c.group_code='SUBJECT' AND c.parent_id IS NOT NULL)";
        } else if ("unclassified".equals(classId)) {
            from += " AND NOT EXISTS (SELECT 1 FROM mc_material_class a WHERE a.material_id = m.id)";
        } else {
            from += classFilter("classId", classId, filters);
        }
        from += classFilter("domainId", domainId, filters) + classFilter("materialTypeId", materialTypeId, filters);
        var result = store.page("m.*", from, "m.captured_at DESC, m.id", filters, page, size, LibraryItem.class);
        var assigned = new HashMap<String, List<String>>();
        var generated = new HashMap<String, List<String>>();
        if (!result.rows().isEmpty()) {
            var ids = result.rows().stream().map(LibraryItem::id).toList();
            for (var type : store.db().sql("SELECT DISTINCT material_id, type_code class_id FROM mc_knowledge WHERE material_id IN (:ids) ORDER BY class_id")
                    .param("ids",ids).query(Assignment.class).list()) {
                generated.computeIfAbsent(type.materialId(), key -> new java.util.ArrayList<>()).add(type.classId());
            }
            for (var relation : store.db().sql("SELECT material_id, class_id FROM mc_material_class WHERE material_id IN (:ids)"
                    + " ORDER BY class_id").param("ids", ids).query(Assignment.class).list()) {
                assigned.computeIfAbsent(relation.materialId(), key -> new java.util.ArrayList<>()).add(relation.classId());
            }
        }
        return new Page<>(result.rows().stream().map(m -> new LibraryRow(m, assigned.getOrDefault(m.id(), List.of()), generated.getOrDefault(m.id(),List.of()))).toList(),
                result.total(), result.page(), result.size());
    }

    private String classFilter(String key, String id, Map<String, Object> params) {
        if (trim(id).isBlank()) return "";
        params.put(key, id);
        return " AND EXISTS (SELECT 1 FROM mc_material_class a JOIN mc_classification c ON c.id = a.class_id"
                + " WHERE a.material_id = m.id AND (c.id = :" + key + " OR c.parent_id = :" + key + "))";
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
