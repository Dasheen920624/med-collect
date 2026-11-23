package com.medcollect;

import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

@Component
class BusinessKnowledge {
    enum Operation { CREATE, UPDATE, SKIP, CHOOSE }
    record Difference(String field, String before, String after) {}
    record Candidate(String id, String title, String knowledgeClass, String doi, String basis, String url) {}
    record Receipt(String remoteId, String url, String type, String childType, String knowledgeClass,
                   boolean categoryMatches, boolean titleMatches, boolean summaryMatches, Boolean pdfMatches) {
        boolean verified() { return categoryMatches && titleMatches && summaryMatches && !Boolean.FALSE.equals(pdfMatches); }
    }
    record Plan(Operation operation, String doi, Candidate target, List<Candidate> candidates,
                List<Difference> differences, ObjectNode before, String baseline,
                Map<String, String> form, boolean uploadPdf) {}

    static final class ReviewChanged extends RuntimeException {
        ReviewChanged(String message) { super(message); }
    }

    private static final Pattern DOI = Pattern.compile("(?i)\\b10\\.[0-9]{4,9}/[-._;()/:a-z0-9]+");
    private static final Pattern UPDATE_MARKER = Pattern.compile("\\s*\\[MC-UPDATE:[a-f0-9-]{36}\\]");
    private static final List<String> BUSINESS_FIELDS = List.of("id", "name", "type", "childType", "knowledgeClass",
            "version", "source", "remark", "modifyTime", "lockStatus", "validFlag", "isStart",
            "submitStatus", "auditStatus", "releaseStatus", "content", "doi");
    private static final String REFERENCES = "references";
    private static final String MANUAL_TARGET = "人工指定同类业务知识";
    private final ObjectMapper json;
    private final CdssClient cdss;
    private final KnowledgeTemplates templates;
    private final KnowledgeWorkflow workflow;

    BusinessKnowledge(ObjectMapper json, CdssClient cdss, KnowledgeTemplates templates, KnowledgeWorkflow workflow) {
        this.json = json;
        this.cdss = cdss;
        this.templates = templates;
        this.workflow = workflow;
    }

    Plan plan(Knowledge draft, Material material, String selectedId) {
        var schema = workflow.schema(draft);
        String doi = firstDoi(material.externalRef(), material.sourceUrl(), material.fetchedUrl());
        var candidates = candidates(draft, material, schema.category(), doi);
        var selected = candidates.stream().filter(item -> item.id().equals(selectedId)).findFirst().orElse(null);
        if (!trim(selectedId).isBlank() && selected == null) throw conflict("该业务记录已移出匹配范围，请重新查重");
        if (candidates.isEmpty()) {
            return new Plan(Operation.CREATE, doi, null, List.of(), List.of(), null, null, Map.of(), material.pdf());
        }
        if (selected == null && candidates.size() > 1) {
            return new Plan(Operation.CHOOSE, doi, null, candidates, List.of(), null, null, Map.of(), false);
        }
        var target = selected == null ? candidates.getFirst() : selected;
        var before = snapshot(target.id(), schema.category());
        if (!inScope(before, draft, schema.category()) || (!target.basis().equals("已关联业务主键")
                && match(before, draft, material, doi) == null)) {
            throw conflict("4.23 记录已变化，请重新查重");
        }
        return compare(draft, material, schema, doi, target, candidates, before);
    }

    void verifyNoMatch(Knowledge draft, Material material) {
        var category = workflow.schema(draft).category();
        if (!candidates(draft, material, category, firstDoi(material.externalRef(), material.sourceUrl(), material.fetchedUrl())).isEmpty()) {
            throw new ReviewChanged("4.23 出现了匹配知识，请重新查重并审核处理方式");
        }
    }

    Page<Candidate> targets(Knowledge draft, String query, int page) {
        if (trim(query).isBlank() || page < 1) throw bad("请输入业务知识名称并选择有效页码");
        var category = workflow.schema(draft).category();
        var filters = scope(category,draft);
        filters.put("name",query.strip());
        var result = cdss.search(filters,page,15);
        var rows = new ArrayList<Candidate>();
        for (var item : result.rows()) {
            if (inScope(item,draft,category)) rows.add(candidate(item,MANUAL_TARGET));
        }
        return new Page<>(rows,result.total(),page,15);
    }

    Plan manualPlan(Knowledge draft, Material material, String targetId) {
        String id = BusinessLiterature.key(targetId);
        var schema = workflow.schema(draft);
        if (draft.updateTargetId() != null && !id.equals(draft.updateTargetId())) throw conflict("修订稿须沿用已关联的业务主键");
        if ("4.23 知识管理".equals(material.sourceName()) && trim(material.externalRef()).startsWith("CDSS:" + schema.category().type() + ":")
                && !material.externalRef().startsWith("CDSS:" + schema.category().type() + ":" + id + ":")) {
            throw conflict("从同类业务记录取得的文献须沿用其原业务主键");
        }
        var before = snapshot(id,schema.category());
        if (!inScope(before,draft,schema.category())) throw conflict("所选知识与稿件的业务类型或子类不符");
        String doi = firstDoi(material.externalRef(),material.sourceUrl(),material.fetchedUrl());
        String nativeDoi = remoteDoi(before);
        if (isLiterature(schema.category().type()) && !doi.isBlank() && !nativeDoi.isBlank() && !doi.equals(nativeDoi)) {
            throw conflict("两篇文献的 DOI 不同，请核对业务目标");
        }
        var target = candidate(before,MANUAL_TARGET);
        return compare(draft,material,schema,doi,target,List.of(target),before);
    }

    private List<Candidate> candidates(Knowledge draft, Material material, KnowledgeTemplates.Category category, String doi) {
        if (draft.updateTargetId() != null) {
            var item = cdss.knowledge(draft.updateTargetId());
            if (!inScope(item, draft, category)) throw conflict("关联知识的类型或类别已变化，请核对业务记录");
            return List.of(candidate(item, "已关联业务主键"));
        }
        if ("4.23 知识管理".equals(material.sourceName()) && trim(material.externalRef()).startsWith("CDSS:")) {
            String[] source=material.externalRef().split(" ",2)[0].split(":",-1);
            if(source.length==4 && source[1].equals(category.type())) {
                var original=cdss.knowledge(BusinessLiterature.key(source[2]));
                if(inScope(original,draft,category)) return List.of(candidate(original,"已关联业务主键"));
                throw conflict("这份业务文献的分类已变更，请核对来源后再审核");
            }
        }
        var scope = scope(category, draft);
        var matches = new LinkedHashMap<String, Candidate>();
        if (!doi.isBlank() && isLiterature(category.type())) {
            search(scope, draft, material, category, doi, matches);
        } else {
            var terms = new LinkedHashSet<String>();
            terms.add(titleQuery(draft.title()));
            if (isLiterature(category.type())) terms.add(titleQuery(material.title()));
            terms.remove("");
            for (String title : terms) {
                var filters = new LinkedHashMap<>(scope);
                filters.put("name", title);
                search(filters, draft, material, category, doi, matches);
            }
        }
        var byDoi = matches.values().stream().filter(row -> row.basis().equals("DOI 一致")).toList();
        return byDoi.isEmpty() ? List.copyOf(matches.values()) : byDoi;
    }

    private void search(Map<String, String> filters, Knowledge draft, Material material, KnowledgeTemplates.Category category,
                        String doi, Map<String, Candidate> matches) {
        int pageSize = 50;
        // The business query has no DOI predicate. Read the selected type/class and match DOI in its source fields.
        for (int page = 1; page <= 30; page++) {
            var result = cdss.search(filters, page, pageSize);
            for (JsonNode row : result.rows()) {
                if (!inScope(row, draft, category)) continue;
                String basis = match(row, draft, material, doi);
                if (basis != null && !row.path("id").asText().isBlank()) {
                    var item = candidate(row, basis);
                    matches.putIfAbsent(item.id(), item);
                }
            }
            if (result.rows().isEmpty() || (result.total() >= 0 ? (long) page * pageSize >= result.total()
                    : result.rows().size() < pageSize)) return;
        }
        throw conflict("4.23 候选范围过大，无法完成查重。请缩小知识子类后再审核");
    }

    private boolean inScope(JsonNode item, Knowledge draft, KnowledgeTemplates.Category category) {
        return category.type().equals(item.path("type").asText())
                && item.path("validFlag").asInt(1) == 1
                && trim(category.childType()).equals(item.path("childType").asText(""))
                && (trim(draft.knowledgeClass()).isBlank() || draft.knowledgeClass().equals(item.path("knowledgeClass").asText()));
    }

    private Map<String,String> scope(KnowledgeTemplates.Category category, Knowledge draft) {
        var filters = new LinkedHashMap<String,String>();
        filters.put("type", category.type());
        if (List.of("17","30","31").contains(category.type())) filters.put("guideType", category.type());
        if (category.childType() != null) filters.put("childType", category.childType());
        if (!trim(draft.knowledgeClass()).isBlank()) filters.put("knowledgeClass", draft.knowledgeClass());
        return filters;
    }

    private String match(JsonNode row, Knowledge draft, Material material, String doi) {
        String existing = titleKey(row.path("name").asText());
        if (!isLiterature(row.path("type").asText())) {
            return !existing.isBlank() && existing.equals(titleKey(draft.title())) ? "类别及知识标题一致" : null;
        }
        String remoteDoi = remoteDoi(row);
        if (!doi.isBlank() && !remoteDoi.isBlank()) return doi.equals(remoteDoi) ? "DOI 一致" : null;
        if (!existing.isBlank() && (existing.equals(titleKey(draft.title())) || existing.equals(titleKey(material.title())))) {
            return "类别及标题一致";
        }
        return null;
    }

    private boolean isLiterature(String type) { return List.of("17","30","31").contains(type); }

    private Candidate candidate(JsonNode row, String basis) {
        String id = row.path("id").asText();
        return new Candidate(id, row.path("name").asText(), row.path("knowledgeClass").asText(""),
                remoteDoi(row), basis, cdss.previewUrl(id));
    }

    private Plan compare(Knowledge draft, Material material, KnowledgeTemplates.Schema schema, String doi,
                         Candidate target, List<Candidate> candidates, ObjectNode original) {
        ObjectNode incoming = templates.cdssContent(schema, material, draft.summary(), workflow.fields(draft));
        ObjectNode oldLayout = layout(original);
        ObjectNode merged = oldLayout == null ? incoming.deepCopy() : oldLayout.deepCopy();
        var changes = new ArrayList<Difference>();
        String savedTitle = MANUAL_TARGET.equals(target.basis()) ? original.path("name").asText() : draft.title();
        add(changes, "标题", original.path("name").asText(), savedTitle);
        if (oldLayout == null && !original.path("content").asText("").isBlank()) {
            add(changes, "原有正文", display(original.path("content")), "按已审核稿件转换为模板正文");
        }
        var values = merged.withObjectProperty("content");
        JsonNode oldValues = oldLayout == null ? json.createObjectNode() : oldLayout.path("content");
        incoming.path("content").fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode old = oldValues.path(key);
            if (sameValue(key, old, entry.getValue())) return;
            if (old instanceof ObjectNode wrapped && wrapped.path("content").isTextual()) {
                values.set(key, wrapped.deepCopy().set("content", entry.getValue()));
            } else {
                values.set(key, entry.getValue());
            }
            ensureField(merged, incoming, key);
            String label = schema.fields().stream().filter(field -> field.id().equals(key)).map(KnowledgeTemplates.Field::label)
                    .findFirst().orElse("abstract".equals(key) ? "摘要" : key);
            changes.add(new Difference(label, differenceText(old), differenceText(entry.getValue())));
        });
        var relationChanges = new LinkedHashMap<String, List<String>>();
        workflow.relations(draft).forEach((key, value) -> {
            var existing = relation(original.path(key));
            if (!existing.equals(value.stream().distinct().sorted().toList())) {
                relationChanges.put(key, value);
                add(changes, relationLabel(key), String.join("、", existing), String.join("、", value));
            }
        });
        boolean attach = material.pdf() && !hasOriginal(original, material);
        if (attach) {
            changes.add(new Difference("PDF 原件", "现有附件保留", material.fileName() + " · SHA-256 " + material.sha256()));
        }
        var form = new LinkedHashMap<String, String>();
        form.put("name", savedTitle);
        form.put("type", schema.category().type());
        form.put("content", merged.toString());
        form.put("remark", updateRemark(original.path("remark").asText(""), draft.id(), doi));
        putRelations(form, relationChanges);
        return new Plan(changes.isEmpty() ? Operation.SKIP : Operation.UPDATE, doi, target, candidates,
                List.copyOf(changes), original, fingerprint(original), Map.copyOf(form), attach);
    }

    ObjectNode snapshot(String remoteId, KnowledgeTemplates.Category category) {
        JsonNode item = cdss.knowledge(remoteId);
        ObjectNode state = json.createObjectNode();
        BUSINESS_FIELDS.forEach(key -> { if (item.hasNonNull(key)) state.set(key, item.get(key)); });
        category.relations().forEach(key -> state.set(key, json.valueToTree(relation(item.path(key)))));
        List<ObjectNode> attachments = new ArrayList<>();
        for (JsonNode row : cdss.attachments(remoteId)) {
            if (row.path("validFlag").asInt(1) != 1) continue;
            var attachment = json.createObjectNode();
            attachment.put("id", row.path("id").asText(""));
            attachment.put("path", DocumentReader.withDefault(row.path("knowledgeAffix").asText(""), row.path("url").asText("")));
            attachment.put("name", DocumentReader.withDefault(row.path("affixName").asText(""), row.path("name").asText("")));
            attachment.put("type", row.path("type").asText(""));
            attachments.add(attachment);
        }
        attachments.sort(Comparator.comparing(itemNode -> itemNode.path("path").asText() + "\n" + itemNode.path("id").asText()));
        state.putArray("attachments").addAll(attachments);
        return state;
    }

    boolean matchesUpdate(Plan plan, ObjectNode current, String draftId, boolean ignoreReferences) {
        if (!plan.form().get("name").equals(current.path("name").asText())
                || !current.path("remark").asText().contains(updateMarker(draftId))) return false;
        for (String key : List.of("type", "childType", "knowledgeClass", "version", "validFlag", "lockStatus")) {
            if (!plan.before().path(key).asText("").equals(current.path(key).asText(""))) return false;
        }
        // The business save route can stop a previously enabled record when its review or release is reset.
        if (plan.before().path("isStart").asInt(0) == 0 && current.path("isStart").asInt(0) != 0) return false;
        ObjectNode desired = parseLayout(plan.form().get("content"));
        ObjectNode actual = layout(current);
        if (actual == null || desired == null) return false;
        var keys = new LinkedHashSet<String>();
        desired.path("content").fieldNames().forEachRemaining(keys::add);
        actual.path("content").fieldNames().forEachRemaining(keys::add);
        if (ignoreReferences) keys.remove(REFERENCES);
        for (String key : keys) {
            if (!sameValue(key, desired.path("content").path(key), actual.path("content").path(key))) return false;
        }
        for (var property : plan.before().properties()) {
            if (!property.getValue().isArray() || property.getKey().equals("attachments")) continue;
            String key = property.getKey();
            if (!reviewedRelation(plan, key).equals(relation(current.path(key)))) return false;
        }
        var currentFiles = current.path("attachments").valueStream().map(file -> file.path("path").asText()).toList();
        for (var old : plan.before().path("attachments")) {
            if (!"1".equals(old.path("type").asText()) && !currentFiles.contains(old.path("path").asText())) return false;
        }
        return true;
    }

    private List<String> reviewedRelation(Plan plan, String key) {
        if (plan.form().containsKey(key)) return List.of(plan.form().get(key));
        var supplied = plan.form().entrySet().stream().filter(e -> e.getKey().startsWith(key + "["))
                .map(Map.Entry::getValue).distinct().sorted().toList();
        return supplied.isEmpty() ? relation(plan.before().path(key)) : supplied;
    }

    // The native save operation rebuilds type-specific dictionary links even when the caller only changes content.
    Map<String,String> updateForm(Plan plan, KnowledgeTemplates.Category category, String content) {
        var payload = new LinkedHashMap<>(plan.form());
        if (category.childType() != null) payload.put("childType", category.childType());
        String classCode = plan.before().path("knowledgeClass").asText("");
        if (!classCode.isBlank()) payload.put("knowledgeClass", classCode);
        for (String key : category.relations()) {
            putRelations(payload, Map.of(key, reviewedRelation(plan,key)));
        }
        payload.put("content", content);
        return payload;
    }

    private boolean hasOriginal(ObjectNode snapshot, Material material) {
        boolean unreadable = false;
        for (var file : snapshot.path("attachments")) {
            if (!isPdf(file)) continue;
            String checksum = cdss.pdfChecksum(file.path("path").asText());
            if (material.sha256().equals(checksum)) return true;
            if (checksum == null) unreadable = true;
        }
        if (unreadable) throw new CdssClient.RemoteFailure("4.23 有 PDF 无法核对原件校验值，请恢复文件读取后重试");
        return false;
    }

    Receipt receipt(Knowledge draft, Material material, ObjectNode actual, Plan plan) {
        var schema = workflow.schema(draft);
        var desired = templates.cdssContent(schema,material,draft.summary(),workflow.fields(draft)).path("content");
        String key = templates.summaryKey(schema, material);
        var body = layout(actual);
        String remote = actual.path("id").asText();
        String expectedClass = plan != null && plan.before() != null ? plan.before().path("knowledgeClass").asText("") : trim(draft.knowledgeClass());
        return new Receipt(remote, cdss.previewUrl(remote), actual.path("type").asText(), actual.path("childType").asText(""),
                actual.path("knowledgeClass").asText(""), inScope(actual,draft,schema.category()) && expectedClass.equals(actual.path("knowledgeClass").asText("")),
                (plan != null ? plan.form().getOrDefault("name",draft.title()) : draft.title()).equals(actual.path("name").asText()), body != null && desired.hasNonNull(key)
                        && sameValue(key, desired.path(key), body.path("content").path(key)),
                material.pdf() ? hasOriginal(actual, material) : null);
    }

    void verifyCreated(Knowledge draft, Material material, ObjectNode actual) {
        var schema = workflow.schema(draft);
        var desired = templates.cdssContent(schema,material,draft.summary(),workflow.fields(draft)).path("content");
        var body = layout(actual);
        if (!actual.path("remark").asText().contains(marker(draft.id())) || !receipt(draft,material,actual,null).verified()
                || !trim(draft.knowledgeClass()).equals(actual.path("knowledgeClass").asText("")) || body == null) {
            throw new CdssClient.RemoteFailure("4.23 新增回读的标题、类别、摘要或 PDF 与审核稿不一致");
        }
        for (var field : desired.properties()) {
            if (!sameValue(field.getKey(),field.getValue(),body.path("content").path(field.getKey()))) {
                throw new CdssClient.RemoteFailure("4.23 未保存审核字段 " + field.getKey());
            }
        }
        workflow.relations(draft).forEach((key, values) -> {
            if (!values.stream().distinct().sorted().toList().equals(relation(actual.path(key)))) {
                throw new CdssClient.RemoteFailure("4.23 未保存已审核的" + relationLabel(key));
            }
        });
    }

    String restoreReferences(Plan plan, ObjectNode current) {
        var expected = parseLayout(plan.form().get("content"));
        var actual = layout(current);
        if (expected == null || actual == null || !plan.uploadPdf()) return null;
        String oldReferences = valueText(expected.path("content").path(REFERENCES));
        String newReferences = valueText(actual.path("content").path(REFERENCES));
        if (oldReferences.isBlank() || newReferences.contains(oldReferences)) return null;
        List<String> newEntries = referenceEntries(newReferences);
        List<String> missing = referenceEntries(oldReferences).stream()
                .filter(entry -> newEntries.stream().noneMatch(present -> sameValue(REFERENCES,
                        json.getNodeFactory().textNode(entry), json.getNodeFactory().textNode(present)))).toList();
        if (missing.isEmpty()) return null;
        actual.withObjectProperty("content").put(REFERENCES, newReferences + String.join("", missing));
        return actual.toString();
    }

    Map<String, String> createForm(Knowledge draft, Material material, KnowledgeTemplates.Schema schema) {
        var category = schema.category();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("name", draft.title());
        form.put("type", category.type());
        if (category.childType() != null) form.put("childType", category.childType());
        if (!trim(draft.knowledgeClass()).isBlank()) form.put("knowledgeClass", draft.knowledgeClass());
        form.put("source", limit(DocumentReader.withDefault(material.sourceName(), "医疗文献采集"), 60));
        String doi = firstDoi(material.externalRef(), material.sourceUrl(), material.fetchedUrl());
        form.put("remark", "来源于知识自动采集 " + marker(draft.id()) + (doi.isBlank() ? "" : " DOI:" + doi));
        form.put("validFlag", "1");
        form.put("content", templates.cdssContent(schema, material, draft.summary(), workflow.fields(draft)).toString());
        putRelations(form, workflow.relations(draft));
        return form;
    }

    private void putRelations(Map<String, String> form, Map<String, List<String>> relations) {
        relations.forEach((key, ids) -> {
            if ("countyUseId".equals(key)) {
                if (!ids.isEmpty()) form.put(key, ids.getFirst());
            } else {
                for (int i = 0; i < ids.size(); i++) form.put(key + "[" + i + "]", ids.get(i));
            }
        });
    }

    private List<String> relation(JsonNode value) {
        if (value.isArray()) return value.valueStream().map(JsonNode::asText).filter(v -> !v.isBlank()).distinct().sorted().toList();
        return value.isValueNode() && !value.isNull() && !value.asText().isBlank() ? List.of(value.asText()) : List.of();
    }

    private String relationLabel(String key) {
        return switch (key) {
            case "icd10Ids" -> "ICD-10 关联";
            case "drugCodes" -> "药品关联";
            case "itemIds" -> "检验检查关联";
            case "operationIds" -> "手术关联";
            case "diseaseIds" -> "疾病关联";
            case "deptCode" -> "科室关联";
            case "countyUseId" -> "县用关联";
            case "nurseIds" -> "护理分类";
            case "treatmentIds" -> "治疗关联";
            case "ruleIds" -> "规则关联";
            default -> key;
        };
    }

    private void ensureField(ObjectNode merged, ObjectNode incoming, String key) {
        if (findField(merged.path("properties"), key) != null || findField(merged.path("catalog"), key) != null) return;
        for (String section : List.of("properties", "catalog")) {
            JsonNode field = findField(incoming.path(section), key);
            if (field != null) {
                var target = merged.get(section) instanceof ArrayNode array ? array : merged.putArray(section);
                var copy = (ObjectNode) field.deepCopy();
                copy.remove("children");
                target.add(copy);
                return;
            }
        }
    }

    private JsonNode findField(JsonNode nodes, String key) {
        for (JsonNode node : nodes) {
            if (key.equals(node.path("id").asText())) return node;
            JsonNode child = findField(node.path("children"), key);
            if (child != null) return child;
        }
        return null;
    }

    static String fingerprint(ObjectNode snapshot) { return sha(snapshot.toString()); }
    static String marker(String id) { return "[MC:" + id + "]"; }
    static String updateMarker(String id) { return "[MC-UPDATE:" + id + "]"; }

    private String updateRemark(String remark, String id, String doi) {
        String preserved = UPDATE_MARKER.matcher(remark).replaceAll("").stripTrailing();
        if (!doi.isBlank() && firstDoi(preserved).isBlank()) preserved += " DOI:" + doi;
        return (preserved.strip() + " " + updateMarker(id)).strip();
    }

    private boolean isPdf(JsonNode node) {
        return node.path("path").asText().toLowerCase(Locale.ROOT).endsWith(".pdf")
                || node.path("name").asText().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    private String remoteDoi(JsonNode row) {
        ObjectNode body = layout(row);
        JsonNode fields = body == null ? json.createObjectNode() : body.path("content");
        return firstDoi(row.path("doi").asText(""), valueText(fields.path("doi")), valueText(fields.path("DOI")),
                valueText(fields.path("laiyuan")), valueText(fields.path("source")), row.path("source").asText(""),
                row.path("remark").asText(""));
    }

    static String firstDoi(String... inputs) {
        for (String input : inputs) {
            String value = trim(input);
            if (value.indexOf('%') >= 0) {
                try { value = URLDecoder.decode(value, StandardCharsets.UTF_8); }
                catch (IllegalArgumentException ignored) { /* An ordinary percent sign need not be URL encoded. */ }
            }
            var matcher = DOI.matcher(value);
            if (matcher.find()) return matcher.group().toLowerCase(Locale.ROOT).replaceFirst("[.;]+$", "");
        }
        return "";
    }

    private ObjectNode layout(JsonNode document) {
        var value = document.path("content");
        return value instanceof ObjectNode object ? object : parseLayout(value.asText(""));
    }

    private ObjectNode parseLayout(String value) {
        if (!trim(value).startsWith("{")) return null;
        try {
            JsonNode parsed = json.readTree(value);
            return parsed instanceof ObjectNode object && object.path("content").isObject() ? object : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    private boolean sameValue(String field, JsonNode left, JsonNode right) {
        if (left.equals(right)) return true;
        if ("laiyuan".equals(field)) {
            String doi = firstDoi(valueText(left));
            if (!doi.isBlank() && doi.equals(firstDoi(valueText(right)))) return true;
        }
        return display(left).equals(display(right)) && links(left).equals(links(right)) && structure(left).equals(structure(right));
    }

    private List<String> links(JsonNode node) {
        return Jsoup.parseBodyFragment(valueText(node)).select("a[href], img[src]").stream()
                .map(element -> element.hasAttr("href") ? element.attr("href") : element.attr("src")).toList();
    }

    private List<String> structure(JsonNode node) {
        return Jsoup.parseBodyFragment(valueText(node)).select("table, tr, th, td, ul, ol, li, sup, sub, del, s")
                .stream().map(element -> element.normalName() + ":" + element.text()).toList();
    }

    private List<String> referenceEntries(String html) {
        var body = Jsoup.parseBodyFragment(html).body();
        return body.childNodes().stream().map(node -> node.outerHtml()).filter(value -> !value.isBlank()).toList();
    }

    private String display(JsonNode value) {
        return Jsoup.parseBodyFragment(valueText(value)).text();
    }

    private String valueText(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) return "";
        if (value.isValueNode()) return value.asText();
        return value.path("content").isTextual() ? value.path("content").asText() : value.toString();
    }

    private String differenceText(JsonNode value) {
        String text = display(value);
        List<String> targets = links(value).stream().filter(link -> !text.contains(link)).toList();
        return targets.isEmpty() ? text : text + "\n" + String.join("\n", targets);
    }

    private void add(List<Difference> list, String label, String before, String after) {
        if (!before.equals(after)) list.add(new Difference(label, before, after));
    }

    private String titleQuery(String title) {
        // Native name search is a literal LIKE; compare normalized full titles locally after finding a shared word.
        var words = Pattern.compile("[\\p{L}\\p{N}]+").matcher(Normalizer.normalize(trim(title), Normalizer.Form.NFKC));
        String longest = "";
        while (words.find()) if (words.group().length() > longest.length()) longest = words.group();
        return limit(longest,60);
    }

    private String titleKey(String title) {
        return Normalizer.normalize(trim(title), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
