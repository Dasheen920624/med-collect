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
    private final TransactionTemplate transactions;

    record Detail(String id, String materialId, String title, String typeCode, String knowledgeClass,
                  String summary, KnowledgeTemplates.Schema schema, Map<String, String> fields,
                  Map<String, List<String>> relations, KnowledgeStatus status, int revision, String modelName,
                  long createdAt, long updatedAt, String remoteId, String remoteUrl, String syncPhase,
                  Long syncLease, String syncError, String updateTargetId) {}

    KnowledgeWorkflow(Store store, TemplateProfiles templates, ModelService model, DocumentReader reader,
                      ObjectMapper json, CdssClient cdss, TransactionTemplate transactions) {
        this.store = store;
        this.templates = templates;
        this.model = model;
        this.reader = reader;
        this.json = json;
        this.cdss = cdss;
        this.transactions = transactions;
    }

    Page<KnowledgeList> list(String query, KnowledgeStatus status, String materialId, int page, int size) {
        Map<String, Object> filters = new HashMap<>();
        String from = "FROM mc_knowledge k JOIN mc_material m ON m.id = k.material_id WHERE 1=1";
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
        return store.page("k.id, k.material_id, m.title material_title, k.title, k.type_code, k.status, k.updated_at, k.remote_id",
                from, "k.updated_at DESC, k.id", filters, page, size, KnowledgeList.class);
    }

    Detail create(String materialId, DraftInput request, String actor) {
        Material material = store.material(materialId);
        if (material.status() != MaterialStatus.APPROVED) throw conflict("请先完成原文审核");
        var schema = templates.schema(request.typeCode(), request.knowledgeClass());
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
        String id = id();
        long now = store.now();
        var params = new MapSqlParameterSource().addValue("id", id).addValue("material", materialId)
                .addValue("title", title).addValue("type", schema.category().code())
                .addValue("class", schema.knowledgeClass(), Types.VARCHAR).addValue("summary", summary, Types.CLOB)
                .addValue("schema", model.write(schema), Types.CLOB).addValue("fields", model.write(fields), Types.CLOB)
                .addValue("relations", "15_11".equals(request.typeCode()) ? "{\"nurseIds\":[\"11\"]}" : "{}", Types.CLOB)
                .addValue("model", modelName, Types.VARCHAR).addValue("now", now);
        String usedModel = modelName;
        return transactions.execute(tx -> {
            store.db().sql("""
                    INSERT INTO mc_knowledge (id, material_id, title, type_code, knowledge_class, summary, template_json,
                      fields_json, relations_json, status, revision, model_name, created_at, updated_at)
                    VALUES (:id, :material, :title, :type, :class, :summary, :schema,
                      :fields, :relations, 'DRAFT', 1, :model, :now, :now)
                    """).paramSource(params).update();
            store.event("KNOWLEDGE", id, usedModel == null ? "CREATED" : "PARSED", actor, usedModel);
            return detail(id);
        });
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
        Material material = store.material(knowledge.materialId());
        Map<String, String> cleanFields = validateFields(material, schema(knowledge),
                input.fields() == null ? Map.of() : input.fields());
        var cleanRelations = validateRelations(schema(knowledge).category(),
                input.relations() == null ? Map.of() : input.relations());
        changed(store.db().sql("""
                UPDATE mc_knowledge SET title = :title, summary = :summary, fields_json = :fields,
                  relations_json = :relations, revision = revision + 1, updated_at = :now
                WHERE id = :id AND revision = :revision AND status IN ('DRAFT', 'RETURNED')
                """).param("title", input.title().strip()).param("summary", trim(input.summary()), Types.CLOB)
                .param("fields", model.write(cleanFields), Types.CLOB).param("relations", model.write(cleanRelations), Types.CLOB)
                .param("now", store.now()).param("id", id).param("revision", input.revision()).update());
        store.event("KNOWLEDGE", id, "EDITED", actor, null);
        return detail(id);
    }

    @Transactional
    Detail submit(String id, int revision, String actor) {
        Knowledge existing = store.knowledge(id);
        requireText(existing.summary(), "请填写摘要后再提交审核");
        var schema = schema(existing);
        if (!store.material(existing.materialId()).pdf() && !schema.rules().isEmpty()) {
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
        String note = input.approved() ? trim(input.note()) : requireText(input.note(), "退回时请填写原因");
        return transition(id, input.revision(), "('PENDING')",
                input.approved() ? KnowledgeStatus.APPROVED : KnowledgeStatus.RETURNED, actor, note);
    }

    ModelService.ReviewAdvice preReview(String id, int revision, String actor) {
        Knowledge draft = store.knowledge(id);
        if (draft.status() != KnowledgeStatus.PENDING || draft.revision() != revision) {
            throw conflict("仅当前待人工审核的版本可发起预审核");
        }
        Material material = store.material(draft.materialId());
        String text = material.pdf() ? reader.pdfForAnalysis(store.bytes(material)) : store.extractedText(material);
        var result = model.review(draft, material, text);
        store.event("KNOWLEDGE", id, "AI_REVIEW", actor, "版本 " + revision + " · " + result.model() + " · "
                + (result.findings().isEmpty() ? "未返回核对提示" : String.join(" / ", result.findings())));
        return result;
    }

    @Transactional
    Detail reopen(String id, int revision, String note, String actor) {
        String reason = requireText(note, "请填写退回修改原因");
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
        if (material.pdf()) return Map.of();
        var accepted = new LinkedHashMap<String, String>();
        for (var field : schema.fields()) {
            if (field.id().equals(schema.summaryKey())) continue;
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
