package com.medcollect;

import static com.medcollect.Support.*;

import com.medcollect.KnowledgeTemplates.Rule;
import java.sql.Types;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TemplateProfiles {
    record Input(@Min(0) int revision,
                 @NotNull @Size(max = 200) Map<@Size(max = 100) String, @NotNull @Valid Rule> rules) {}
    private record RuleSet(Map<String, KnowledgeTemplates.Rule> rules) {}
    private record Saved(String rulesJson, int revision) {}
    private final Store store;
    private final KnowledgeTemplates templates;
    private final ModelService model;

    TemplateProfiles(Store store, KnowledgeTemplates templates, ModelService model) {
        this.store = store;
        this.templates = templates;
        this.model = model;
    }

    void availableForCollection(String code) { templates.availableForCollection(code); }

    KnowledgeTemplates.Schema schema(String code, String knowledgeClass) {
        var base = templates.schema(code, knowledgeClass);
        return store.db().sql("SELECT rules_json, revision FROM mc_template_profile WHERE profile_key = :key")
                .param("key", key(base)).query(Saved.class).optional()
                .map(saved -> withRules(base, model.read(saved.rulesJson(), RuleSet.class).rules(),
                        saved.revision())).orElse(base);
    }

    @Transactional
    KnowledgeTemplates.Schema save(String code, String knowledgeClass, Input input, String actor) {
        var base = templates.schema(code, knowledgeClass);
        Set<String> editable = editableFields(base);
        Map<String, KnowledgeTemplates.Rule> rules = new LinkedHashMap<>();
        for (var entry : input.rules().entrySet()) {
            if (!editable.contains(entry.getKey())) throw bad("字段不属于当前知识模板");
            var rule = entry.getValue();
            String instruction = trim(rule.instruction());
            if (!instruction.isBlank() || rule.required()) rules.put(entry.getKey(),
                    new KnowledgeTemplates.Rule(instruction, rule.required()));
        }
        String query = input.revision() == 0 ? """
                INSERT INTO mc_template_profile (profile_key, rules_json, revision, updated_at)
                VALUES (:key, :rules, 1, :now)
                """ : """
                UPDATE mc_template_profile SET rules_json = :rules, revision = revision + 1,
                  updated_at = :now WHERE profile_key = :key AND revision = :revision
                """;
        try {
            changed(store.db().sql(query).param("key", key(base)).param("rules", model.write(new RuleSet(rules)), Types.CLOB)
                    .param("revision", input.revision()).param("now", store.now()).update());
        } catch (DuplicateKeyException e) {
            throw conflict("模板已更新，请刷新后重试");
        }
        store.event("TEMPLATE", sha(key(base)).substring(0, 36), "UPDATED", actor, key(base));
        return withRules(base, rules, input.revision() + 1);
    }

    private KnowledgeTemplates.Schema withRules(KnowledgeTemplates.Schema base, Map<String, KnowledgeTemplates.Rule> rules, int revision) {
        var valid = editableFields(base);
        var current = new LinkedHashMap<>(rules);
        current.keySet().retainAll(valid);
        return new KnowledgeTemplates.Schema(base.category(), base.knowledgeClass(), base.summaryKey(), base.fields(),
                base.layout(), base.origin(), current, revision);
    }

    private Set<String> editableFields(KnowledgeTemplates.Schema schema) {
        return schema.fields().stream().filter(f -> !f.dictionary() && !Set.of("references", "laiyuan", "guideType").contains(f.id()))
                .map(KnowledgeTemplates.Field::id).collect(Collectors.toSet());
    }

    java.util.List<Records.Event> events(String code, String knowledgeClass) {
        var base = templates.schema(code, knowledgeClass);
        return store.events("TEMPLATE", sha(key(base)).substring(0, 36));
    }

    private String key(KnowledgeTemplates.Schema schema) {
        return schema.category().code() + ":" + trim(schema.knowledgeClass());
    }
}
