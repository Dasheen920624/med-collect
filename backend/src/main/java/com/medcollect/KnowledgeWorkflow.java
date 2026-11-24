package com.medcollect;

import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.sql.Types;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class KnowledgeWorkflow {
    private final Store store;
    private final TemplateProfiles templates;
    private final ModelService model;
    private final DocumentReader reader;
    private final ObjectMapper json;
    private final CdssClient cdss;
    private final BusinessDictionaries dictionaries;
    private final TransactionTemplate transactions;
    private final List<String> manualHosts;

    record Detail(String id, String materialId, String title, String typeCode, String knowledgeClass,
                  String summary, KnowledgeTemplates.Schema schema, Map<String, String> fields,
                  Map<String, List<String>> relations, KnowledgeStatus status, int revision, String modelName,
                  long createdAt, long updatedAt, String remoteId, String remoteUrl, String syncPhase,
                  Long syncLease, String syncError, String updateTargetId) {}

    KnowledgeWorkflow(Store store, TemplateProfiles templates, ModelService model, DocumentReader reader,
                      ObjectMapper json, CdssClient cdss, TransactionTemplate transactions, BusinessDictionaries dictionaries, AppSettings settings) {
        this.store = store;
        this.templates = templates;
        this.model = model;
        this.reader = reader;
        this.json = json;
        this.cdss = cdss;
        this.dictionaries = dictionaries;
        this.transactions = transactions;
        manualHosts = (settings.manualSourceHosts()==null ? List.<String>of() : settings.manualSourceHosts()).stream().map(Support::trim).filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT)).toList();
    }

    Page<KnowledgeList> list(String query, KnowledgeStatus status, String materialId, String human, String sync,
                             String subjectId, String submission, String publication, String classificationId,
                             String typeCode, String knowledgeClass, int page, int size) {
        return list(query, status, materialId, human, sync, subjectId, submission, publication, classificationId, typeCode, knowledgeClass, page, size, false);
    }

    Page<KnowledgeList> forMaterial(String materialId, boolean archived) {
        return list(null, null, materialId, null, null, null, null, null, null, null, null, 1, 20, archived);
    }

    private Page<KnowledgeList> list(String query, KnowledgeStatus status, String materialId, String human, String sync,
                                     String subjectId, String submission, String publication, String classificationId,
                                     String typeCode, String knowledgeClass, int page, int size, boolean archived) {
        Map<String, Object> filters = new HashMap<>();
        String from = "FROM mc_knowledge k JOIN mc_material m ON m.id = k.material_id WHERE m.archived_at "
                + (archived ? "IS NOT NULL" : "IS NULL");
        if (!trim(query).isBlank()) {
            from += " AND (INSTR(LOWER(k.title), :q) > 0 OR INSTR(LOWER(m.title), :q) > 0)";
            filters.put("q", query.strip().toLowerCase(Locale.ROOT));
        }
        if (status != null) {
            from += " AND k.status = :status";
            filters.put("status", status.name());
        }
        if (!trim(materialId).isBlank()) {
            from += " AND k.material_id = :material";
            filters.put("material", materialId);
        }
        if (!trim(classificationId).isBlank()) {
            from += """
                     AND EXISTS (SELECT 1 FROM mc_material_class a JOIN mc_classification c ON c.id=a.class_id
                        WHERE a.material_id=m.id AND (c.id=:classification OR c.parent_id=:classification))
                    """;
            filters.put("classification", classificationId);
        }
        if (!trim(typeCode).isBlank()) {
            from += " AND k.type_code=:typeCode";
            filters.put("typeCode", typeCode);
        }
        if (!trim(knowledgeClass).isBlank()) {
            from += " AND k.knowledge_class=:knowledgeClass";
            filters.put("knowledgeClass", knowledgeClass);
        }
        from += Classifications.subjectScope("k.material_id", subjectId, filters);
        if ("SUBMITTED".equals(submission)) from += " AND k.status <> 'DRAFT'";
        if ("UNSUBMITTED".equals(submission)) from += " AND k.status = 'DRAFT'";
        if ("PUBLISHED".equals(publication)) from += " AND k.status = 'SYNCED' AND k.business_release = '1'";
        if ("UNPUBLISHED".equals(publication)) from += " AND (k.remote_id IS NULL OR k.business_release IN ('0','2'))";
        if ("UNKNOWN".equals(publication)) from += " AND k.remote_id IS NOT NULL AND k.business_release IS NULL";
        if ("EDITABLE".equals(human)) {
            from += " AND k.status IN ('DRAFT','RETURNED')";
        } else if ("REVIEWED".equals(human)) {
            from += " AND k.status IN ('APPROVED','SYNCING','SYNC_FAILED','SYNCED','RETURNED','SYNC_DECLINED')";
        } else if ("UNREVIEWED".equals(human)) {
            from += " AND k.status IN ('PENDING','DRAFT','REFERENCE_ONLY')";
        } else if (!trim(human).isBlank()) {
            from += " AND (CASE WHEN k.status IN ('DRAFT','PENDING','RETURNED','REFERENCE_ONLY','SYNC_DECLINED') THEN k.status ELSE 'APPROVED' END) = :human";
            filters.put("human", human);
        }
        if ("READY".equals(sync)) {
            from += " AND k.status = 'APPROVED'";
        } else if (!trim(sync).isBlank()) {
            from += " AND (CASE WHEN k.sync_phase = 'SKIPPED' THEN 'SKIPPED' WHEN k.status IN"
                    + " ('SYNCING','SYNCED','SYNC_FAILED','REFERENCE_ONLY','SYNC_DECLINED') THEN k.status ELSE 'UNSYNCED' END) = :sync";
            filters.put("sync", sync);
        }
        String columns = """
                k.id, k.material_id, m.title material_title, k.title, k.type_code, k.knowledge_class, k.status, k.updated_at, k.remote_id, k.sync_phase, k.business_release,
                (SELECT MAX(v.version_no) FROM mc_sync_version v WHERE v.knowledge_id = k.id) version_no,
                (SELECT e.actor FROM mc_event e WHERE e.entity_type = 'KNOWLEDGE' AND e.entity_id = k.id
                  AND e.action IN ('APPROVED','RETURNED','SYNC_DECLINED') ORDER BY e.occurred_at DESC, e.id DESC FETCH FIRST 1 ROW ONLY) reviewer,
                (SELECT MAX(e.occurred_at) FROM mc_event e WHERE e.entity_type = 'KNOWLEDGE' AND e.entity_id = k.id
                  AND e.action IN ('APPROVED','RETURNED','SYNC_DECLINED')) reviewed_at,
                (SELECT MAX(e.occurred_at) FROM mc_event e WHERE e.entity_type = 'KNOWLEDGE' AND e.entity_id = k.id
                  AND e.action = 'AI_REVIEW') ai_reviewed_at,
                CASE WHEN k.status <> 'SYNCED' THEN NULL WHEN k.sync_phase = 'SKIPPED' THEN 'SKIP'
                  ELSE COALESCE((SELECT MAX(v.operation) FROM mc_sync_version v WHERE v.knowledge_id = k.id),
                    (SELECT MAX('CREATE') FROM mc_event e WHERE e.entity_type = 'KNOWLEDGE' AND e.entity_id = k.id AND e.action = 'SYNC_CREATED'))
                  END delivered_operation
                """;
        return store.page(columns,
                from, "k.updated_at DESC, k.id", filters, page, size, KnowledgeList.class);
    }

    Detail create(String materialId, DraftInput request, String actor) {
        Material material = store.requireActive(store.material(materialId));
        requireSource(material,request.typeCode());
        if (material.status() != MaterialStatus.APPROVED) throw conflict("请先完成原文审核");
        templates.availableForCollection(request.typeCode());
        var schema = templates.draftSchema(request.typeCode(), request.knowledgeClass());
        String id = request.requestId() == null ? id() : request.requestId().toString();
        if (request.requestId() != null) {
            Detail existing = existingDraft(id, materialId, schema, request.useAi());
            if (existing != null) return existing;
        }
        String attempt = id();
        String reusable = claimParse(materialId, schema, attempt, request.reuseExisting());
        if (reusable != null) return detail(reusable);
        try {
            Detail result = generate(material,request,schema,id,actor);
            finishParse(attempt,"COMPLETED");
            return result;
        } catch (RuntimeException e) {
            finishParse(attempt,"FAILED");
            throw e;
        }
    }

    private String claimParse(String materialId, KnowledgeTemplates.Schema schema, String attempt, boolean reuseExisting) {
        return transactions.execute(tx -> {
            store.lockActiveMaterial(materialId);
            var params = new MapSqlParameterSource().addValue("material",materialId)
                    .addValue("type",schema.category().code()).addValue("category",schema.knowledgeClass(),Types.VARCHAR);
            if (reuseExisting) {
                var result = store.db().sql("""
                        SELECT id FROM mc_knowledge WHERE material_id=:material AND type_code=:type
                        AND (knowledge_class=:category OR (knowledge_class IS NULL AND :category IS NULL))
                        ORDER BY created_at DESC, id DESC FETCH FIRST 1 ROW ONLY
                        """).paramSource(params).query(String.class).optional();
                if (result.isPresent()) return result.get();
                long active = store.db().sql("""
                        SELECT COUNT(*) FROM mc_parse_attempt WHERE material_id=:material AND state='RUNNING' AND started_at>:since
                        AND (type_code IS NULL OR (type_code=:type AND (class_code=:category OR (class_code IS NULL AND :category IS NULL))))
                        """).paramSource(params.addValue("since",store.now() - MaterialService.PARSE_TIMEOUT)).query(Long.class).single();
                if (active > 0) throw conflict("该模板正在解析，请稍后查看解析结果");
            }
            store.db().sql("""
                    INSERT INTO mc_parse_attempt (id, material_id, type_code, class_code, state, started_at)
                    VALUES (:id, :material, :type, :category, 'RUNNING', :now)
                    """).paramSource(params.addValue("id",attempt).addValue("now",store.now())).update();
            return null;
        });
    }

    private void finishParse(String attempt,String state) {
        store.db().sql("UPDATE mc_parse_attempt SET state=:state, finished_at=:now WHERE id=:id")
                .param("state",state).param("now",store.now()).param("id",attempt).update();
    }

    private Detail generate(Material material,DraftInput request,KnowledgeTemplates.Schema schema,String id,String actor) {
        String materialId = material.id();
        String title = limit(material.title(), 100);
        String summary = material.sourceAbstract();
        String modelName = null;
        Map<String, String> fields = Map.of();
        if (request.useAi()) {
            String text = material.pdf() ? reader.pdfForAnalysis(store.bytes(material)) : store.extractedText(material);
            if (trim(text).isBlank() && trim(summary).isBlank()) {
                throw bad("未取得可读文字，请手工录入摘要");
            }
            var result = model.analyze(material, schema, trim(text));
            title = result.title();
            summary = result.summary();
            modelName = result.model();
            fields = result.fields();
        }
        var text = KnowledgeText.separate(summary, fields, material);
        long now = store.now();
        var params = new MapSqlParameterSource().addValue("id", id).addValue("material", materialId)
                .addValue("title", title).addValue("type", schema.category().code())
                .addValue("class", schema.knowledgeClass(), Types.VARCHAR).addValue("summary", text.summary(), Types.CLOB)
                .addValue("schema", model.write(schema), Types.CLOB).addValue("fields", model.write(text.fields()), Types.CLOB)
                .addValue("relations", "15_11".equals(request.typeCode()) ? "{\"nurseIds\":[\"11\"]}" : "{}", Types.CLOB)
                .addValue("model", modelName, Types.VARCHAR).addValue("now", now);
        String usedModel = modelName;
        try {
            return transactions.execute(tx -> {
                store.lockActiveMaterial(materialId);
                store.db().sql("""
                    INSERT INTO mc_knowledge (id, material_id, title, type_code, knowledge_class, summary, template_json,
                      fields_json, relations_json, status, revision, model_name, created_at, updated_at)
                    VALUES (:id, :material, :title, :type, :class, :summary, :schema,
                      :fields, :relations, 'DRAFT', 1, :model, :now, :now)
                    """).paramSource(params).update();
                store.event("KNOWLEDGE", id, usedModel == null ? "CREATED" : "PARSED", actor, usedModel);
                return detail(id);
            });
        } catch (org.springframework.dao.DuplicateKeyException e) {
            Detail existing = existingDraft(id, materialId, schema, request.useAi());
            if (existing != null) return existing;
            throw e;
        }
    }

    private Detail existingDraft(String id, String materialId, KnowledgeTemplates.Schema schema, boolean useAi) {
        var existing = store.db().sql("SELECT * FROM mc_knowledge WHERE id = :id").param("id", id)
                .query(Knowledge.class).optional();
        if (existing.isEmpty()) return null;
        Knowledge k = existing.get();
        boolean generatedByModel = !trim(k.modelName()).isBlank();
        if (!k.materialId().equals(materialId) || !k.typeCode().equals(schema.category().code())
                || !trim(k.knowledgeClass()).equals(trim(schema.knowledgeClass())) || useAi != generatedByModel) {
            throw conflict("同一解析请求已用于其他原文或模板");
        }
        return detail(id);
    }

    Detail detail(String id) {
        Knowledge knowledge = store.knowledge(id);
        return new Detail(knowledge.id(), knowledge.materialId(), knowledge.title(), knowledge.typeCode(),
                knowledge.knowledgeClass(), knowledge.summary(), schema(knowledge), fields(knowledge), relations(knowledge),
                knowledge.status(), knowledge.revision(), knowledge.modelName(), knowledge.createdAt(), knowledge.updatedAt(),
                knowledge.remoteId(), knowledge.remoteId() == null ? null : cdss.previewUrl(knowledge.remoteId()),
                knowledge.syncPhase(), knowledge.syncLease(), knowledge.syncError(), knowledge.updateTargetId());
    }

    @Transactional
    Detail edit(String id, DraftEdit input, String actor) {
        Knowledge knowledge = store.knowledge(id);
        Material material = store.lockActiveMaterial(knowledge.materialId());
        var selectedSchema = schema(knowledge);
        if (input.knowledgeClass() != null && !trim(knowledge.knowledgeClass()).equals(trim(input.knowledgeClass()))) {
            if (knowledge.updateTargetId() != null) throw conflict("修订稿须沿用原业务知识类别");
            selectedSchema = templates.schema(knowledge.typeCode(), requireText(input.knowledgeClass(),"请选择知识类别"));
        }
        Map<String, String> cleanFields = validateFields(material, selectedSchema,
                input.fields() == null ? Map.of() : input.fields());
        var text = KnowledgeText.separate(input.summary(), cleanFields, material);
        var cleanRelations = validateRelations(selectedSchema.category(),
                input.relations() == null ? Map.of() : input.relations());
        if (cdss.configured()) dictionaries.validate(selectedSchema,cleanFields,cleanRelations,false);
        changed(store.db().sql("""
                UPDATE mc_knowledge SET title = :title, summary = :summary, fields_json = :fields,
                  knowledge_class = :classCode, template_json = :schema, relations_json = :relations, revision = revision + 1, updated_at = :now
                WHERE id = :id AND revision = :revision AND status IN ('DRAFT', 'RETURNED')
                """).param("title", input.title().strip()).param("summary", text.summary(), Types.CLOB)
                .param("fields", model.write(text.fields()), Types.CLOB).param("relations", model.write(cleanRelations), Types.CLOB)
                .param("classCode",selectedSchema.knowledgeClass(),Types.VARCHAR).param("schema",model.write(selectedSchema),Types.CLOB)
                .param("now", store.now()).param("id", id).param("revision", input.revision()).update());
        store.event("KNOWLEDGE", id, "EDITED", actor, null);
        return detail(id);
    }

    @Transactional
    Detail submit(String id, int revision, String actor) {
        Knowledge existing = store.knowledge(id);
        Material material = store.lockActiveMaterial(existing.materialId());
        requireText(existing.summary(), "请填写摘要后再提交审核");
        var schema = schema(existing);
        validateBusinessValues(existing,false);
        if (!material.pdf() && !schema.rules().isEmpty()) {
            var values = fields(existing);
            for (var field : schema.fields()) {
                var rule = schema.rules().get(field.id());
                if (rule != null && rule.required() && !field.id().equals(schema.summaryKey())) {
                    requireText(values.get(field.id()), "请填写必填模板字段：" + field.label());
                }
            }
        }
        return transition(id, revision, "('DRAFT', 'RETURNED')", KnowledgeStatus.PENDING, actor, null);
    }

    @Transactional
    Detail decision(String id, Decision input, String actor) {
        if (input.approved()) {
            Knowledge knowledge = store.knowledge(id);
            requireSource(store.material(knowledge.materialId()),knowledge.typeCode());
        }
        String note = input.approved() ? trim(input.note()) : requireText(input.note(), "退回时请填写原因");
        return transition(id, input.revision(), "('PENDING')",
                input.approved() ? KnowledgeStatus.APPROVED : KnowledgeStatus.RETURNED, actor, note);
    }

    ModelService.ReviewAdvice preReview(String id, int revision, String actor) {
        Knowledge draft = store.knowledge(id);
        if (draft.status() != KnowledgeStatus.PENDING || draft.revision() != revision) {
            throw conflict("仅当前待人工审核的版本可发起预审核");
        }
        Material material = store.requireActive(store.material(draft.materialId()));
        String text = material.pdf() ? reader.pdfForAnalysis(store.bytes(material)) : store.extractedText(material);
        var result = model.review(draft, material, text);
        store.event("KNOWLEDGE", id, "AI_REVIEW", actor, "版本 " + revision + " · " + result.model() + " · "
                + (result.findings().isEmpty() ? "未返回核对提示" : String.join(" / ", result.findings())));
        return result;
    }

    @Transactional
    Detail reopen(String id, int revision, String note, String actor) {
        String reason = requireText(note, "请填写退回修改原因");
        store.lockActiveMaterial(store.knowledge(id).materialId());
        changed(store.db().sql("""
                UPDATE mc_knowledge SET status = 'RETURNED', revision = revision + 1, updated_at = :now,
                  sync_phase = NULL, sync_error = NULL WHERE id = :id AND revision = :revision
                  AND status IN ('APPROVED', 'SYNC_FAILED') AND remote_id IS NULL AND sync_phase IS NULL
                """).param("id", id).param("revision", revision).param("now", store.now()).update());
        store.event("KNOWLEDGE", id, "RETURNED", actor, reason);
        return detail(id);
    }

    private Detail transition(String id, int revision, String from, KnowledgeStatus next, String actor, String note) {
        changed(store.db().sql("""
                UPDATE mc_knowledge SET status = :next, revision = revision + 1, updated_at = :now
                WHERE id = :id AND revision = :revision AND status IN
                """ + from).param("next", next.name()).param("now", store.now())
                .param("id", id).param("revision", revision).update());
        store.event("KNOWLEDGE", id, next.name(), actor, note);
        return detail(id);
    }

    void validateBusinessValues(Knowledge k,boolean fresh) {
        requireSource(store.material(k.materialId()),k.typeCode());
        if (cdss.configured()) dictionaries.validate(schema(k),fields(k),relations(k),fresh);
    }

    void requireSource(Material material,String type) {
        if (manualHosts.isEmpty() || !List.of("8-1","8-2").contains(type)) return;
        if (!allowedHost(material.sourceUrl()) || (!trim(material.fetchedUrl()).isEmpty() && !allowedHost(material.fetchedUrl()))) {
            throw conflict("说明书需要使用已核实的中国大陆发布机构原件，请核对来源网址");
        }
    }

    private boolean allowedHost(String url) {
        try {
            var uri=java.net.URI.create(trim(url));
            return uri.getHost()!=null && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && manualHosts.contains(uri.getHost().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) { return false; }
    }

    KnowledgeTemplates.Schema schema(Knowledge knowledge) {
        return model.read(knowledge.templateJson(), KnowledgeTemplates.Schema.class);
    }

    Map<String, String> fields(Knowledge knowledge) {
        return read(knowledge.fieldsJson(), new TypeReference<>() {});
    }

    Map<String, List<String>> relations(Knowledge knowledge) {
        return read(knowledge.relationsJson(), new TypeReference<>() {});
    }

    private <T> T read(String input, TypeReference<T> type) {
        try {
            return json.readValue(input, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid stored workflow payload", e);
        }
    }

    private Map<String, String> validateFields(Material material, KnowledgeTemplates.Schema schema, Map<String, String> fields) {
        var accepted = new LinkedHashMap<String, String>();
        for (var field : schema.fields()) {
            if (field.id().equals(schema.summaryKey())) continue;
            if (material.pdf() && !KnowledgeText.SOURCE.equals(field.id())) continue;
            String value = trim(fields.get(field.id()));
            if (value.length() > 12000) throw bad(field.label() + "超过 12000 字符");
            if (!value.isBlank()) accepted.put(field.id(), value);
        }
        return accepted;
    }

    private Map<String, List<String>> validateRelations(KnowledgeTemplates.Category category, Map<String, List<String>> relations) {
        var accepted = new LinkedHashMap<String, List<String>>();
        for (String key : category.relations()) {
            List<String> incoming = relations.get(key);
            if (incoming == null) continue;
            if (incoming.size() > 50 || incoming.stream().anyMatch(s -> trim(s).length() > 80)) {
                throw bad("字典关联最多 50 项，每项最多 80 字符");
            }
            List<String> ids = incoming.stream().map(Support::trim).filter(s -> !s.isBlank()).distinct().toList();
            if ("countyUseId".equals(key) && ids.size() > 1) throw bad("是否县用只能选择一个值");
            if (!ids.isEmpty()) accepted.put(key, ids);
        }
        if (category.code().equals("15_11") && !accepted.getOrDefault("nurseIds", List.of()).contains("11")) {
            throw bad("标本采集模板需要保留护理分类 ID 11");
        }
        if (category.code().equals("15") && accepted.getOrDefault("nurseIds", List.of()).contains("11")) {
            throw bad("护理分类 ID 11 请使用标本采集知识模板");
        }
        return accepted;
    }
}
