package com.medcollect;

import static com.medcollect.Support.*;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
class KnowledgeTemplates {
    record Category(String code, String name, String type, String childType, String classRoot,
                    boolean classRequired, String templateKey, List<String> relations) {}
    record ClassOption(String code, String name, String parent) {}
    record Field(String id, String label, boolean property, boolean dictionary) {}
    record Rule(@jakarta.validation.constraints.Size(max = 1000) String instruction, boolean required) {}
    record Schema(Category category, String knowledgeClass, String summaryKey, List<Field> fields, ObjectNode layout,
                  String origin, Map<String, Rule> rules, int profileRevision) {
        Schema { rules = rules == null ? Map.of() : Map.copyOf(rules); }
    }
    private record Cache<T>(T value, long expires) {}

    private static final List<Category> CATEGORIES = List.of(
            category("1", "疾病知识", "icd10Ids"),
            category("6", "药品说明书", "drugCodes"),
            new Category("24", "中药知识", "24", null, "24", true, "24", List.of("icd10Ids")),
            new Category("8-1", "检验知识", "8", "8-1", "8", false, "8-1", List.of("itemIds")),
            new Category("8-2", "检查知识", "8", "8-2", "8", false, "8-2", List.of("itemIds")),
            category("9", "症状知识", "diseaseIds"),
            category("10", "临床路径知识", "deptCode", "icd10Ids", "countyUseId"),
            new Category("17", "临床指南", "17", null, "17", true, "17", List.of("icd10Ids")),
            new Category("30", "医学书籍", "30", null, "17", true, "17", List.of()),
            new Category("31", "医学期刊", "31", null, "17", true, "17", List.of()),
            category("11", "手术知识", "operationIds"),
            category("3", "治疗知识", "treatmentIds"),
            category("13", "麻醉知识"),
            category("14", "输血知识"),
            category("15", "护理知识", "diseaseIds", "nurseIds", "operationIds", "itemIds"),
            new Category("15_11", "护理知识 · 标本采集", "15", null, "15", false, "15_11",
                    List.of("diseaseIds", "nurseIds", "operationIds", "itemIds")),
            category("18", "核心制度知识"),
            category("2", "患者指导"),
            category("4", "用药建议（旧类）"),
            category("5", "检查建议（旧类）"),
            category("7", "出院指导（旧类）"),
            category("12", "急救知识"),
            category("16", "药物护理知识"),
            category("32", "典型病例"),
            category("38", "规则来源", "ruleIds"),
            category("1001", "诊断依据", "icd10Ids"),
            category("1002", "治疗方案", "icd10Ids"),
            category("1003", "检查建议", "icd10Ids"),
            category("1004", "用药建议", "icd10Ids"),
            category("1005", "健康教育", "icd10Ids"),
            category("1006", "出院指导", "icd10Ids"));

    private static final List<ClassOption> TCM_CLASSES = List.of(
            new ClassOption("2401", "方剂", "24"), new ClassOption("2402", "药膳食疗", "24"),
            new ClassOption("2403", "针灸穴位", "24"), new ClassOption("2404", "中药材", "24"));
    private final JsonNode snapshot;
    private final CdssClient cdss;
    private final Clock clock;
    private final ObjectMapper json;
    private volatile Cache<JsonNode> templateCache;
    private final Map<String, Cache<List<ClassOption>>> classCache = new ConcurrentHashMap<>();

    KnowledgeTemplates(CdssClient cdss, Clock clock, ObjectMapper json) throws IOException {
        this.cdss = cdss;
        this.clock = clock;
        this.json = json;
        try (var input = new ClassPathResource("cdss/knowledge-templates.json").getInputStream()) {
            this.snapshot = json.readTree(input);
        }
    }

    private static Category category(String code, String name, String... relations) {
        return new Category(code, name, code, null, code, false, code, List.of(relations));
    }

    List<Category> categories() { return CATEGORIES; }

    Category category(String code) {
        return CATEGORIES.stream().filter(c -> c.code().equals(code)).findFirst()
                .orElseThrow(() -> bad("请选择 4.23 支持的知识类别"));
    }

    List<ClassOption> classes(String code) {
        Category category = category(code);
        try {
            List<ClassOption> live = liveClasses(category.classRoot());
            if (!live.isEmpty()) {
                if (code.equals("24")) return live.stream()
                        .filter(option -> TCM_CLASSES.stream().anyMatch(c -> c.code().equals(option.code()))).toList();
                return live;
            }
        } catch (CdssClient.RemoteFailure ignored) {
            // Use only known top-level or template-backed classes during local preparation.
        }
        if (code.equals("24")) return TCM_CLASSES;
        if (category.classRoot().equals("17")) return List.of(new ClassOption("17", "临床指南知识", "00"));
        return List.of();
    }

    void validateClass(Category category, String classCode, boolean live) {
        String value = trim(classCode);
        if (value.isBlank()) {
            if (category.classRequired()) throw bad("请选择目标系统中的知识子类");
            return;
        }
        List<ClassOption> allowed = live ? liveClasses(category.classRoot()) : classes(category.code());
        if (allowed.stream().noneMatch(c -> value.equals(c.code()))
                || (category.code().equals("24") && TCM_CLASSES.stream().noneMatch(c -> value.equals(c.code())))) {
            throw bad("知识子类不属于当前类型，或已从 4.23 类别字典移除");
        }
    }

    private List<ClassOption> liveClasses(String root) {
        if (!cdss.configured()) return List.of();
        Cache<List<ClassOption>> found = classCache.get(root);
        if (found != null && clock.millis() < found.expires()) return found.value();
        JsonNode rows = cdss.classes(root);
        var options = new LinkedHashMap<String, ClassOption>();
        for (JsonNode row : rows) {
            String code = row.path("knowledgeClass").asText();
            if (!code.isBlank()) options.putIfAbsent(code, new ClassOption(code,
                    row.path("knowledgeClassName").asText(), row.path("upperClass").asText()));
        }
        List<ClassOption> result = List.copyOf(options.values());
        classCache.put(root, new Cache<>(result, expires()));
        return result;
    }

    Schema schema(String typeCode, String classCode) {
        var category = category(typeCode);
        validateClass(category, classCode, false);
        JsonNode live = currentTemplates();
        String key = typeCode.equals("24") ? classCode : category.templateKey();
        JsonNode template = live.path(key);
        if (!template.isObject()) template = snapshot.path(key);
        if (!template.isObject()) template = live.path("9999");
        if (!template.isObject()) template = snapshot.path("9999");
        ObjectNode layout = ((ObjectNode) template).deepCopy();
        layout.set("content", json.createObjectNode());
        if (!layout.path("properties").isArray()) layout.set("properties", json.createArrayNode());
        if (!layout.path("catalog").isArray()) layout.set("catalog", json.createArrayNode());
        var fields = fields(layout);
        String summary = List.of("abstract", "summary", "details", "jieda", "caseSummary", "sourceExplain",
                        "guideEvidence", "objective", "lab").stream()
                .filter(candidate -> fields.stream().anyMatch(field -> field.id().equals(candidate))).findFirst()
                .orElse("summary");
        var catalog = (ArrayNode) layout.path("catalog");
        ensureSection(catalog, fields, summary, "摘要");
        ensureSection(catalog, fields, "references", "参考文献与原件");
        ensureSection(catalog, fields, "laiyuan", "资料来源");
        return new Schema(category, trim(classCode), summary, fields(layout), layout,
                live == snapshot ? "模板快照" : "4.23 在线模板", Map.of(), 0);
    }

    private void ensureSection(ArrayNode catalog, List<Field> fields, String key, String label) {
        if (fields.stream().noneMatch(field -> field.id().equals(key))) {
            catalog.addObject().put("id", key).put("label", label);
        }
    }

    private List<Field> fields(ObjectNode layout) {
        Map<String, Field> result = new LinkedHashMap<>();
        collect(layout.path("properties"), true, result);
        collect(layout.path("catalog"), false, result);
        return new ArrayList<>(result.values());
    }

    private void collect(JsonNode nodes, boolean property, Map<String, Field> fields) {
        for (JsonNode node : nodes) {
            String name = node.path("id").asText();
            if (!name.isBlank()) fields.putIfAbsent(name, new Field(name, DocumentReader.withDefault(
                    node.path("label").asText(), name), property, "select".equals(node.path("type").asText())));
            collect(node.path("children"), property, fields);
        }
    }

    JsonNode refresh() {
        JsonNode stats = cdss.verifyConnection();
        templateCache = new Cache<>(cdss.templates(), expires());
        classCache.clear();
        return stats;
    }

    private JsonNode currentTemplates() {
        if (!cdss.configured()) return snapshot;
        Cache<JsonNode> cache = templateCache;
        if (cache != null && clock.millis() < cache.expires()) return cache.value();
        try {
            var live = cdss.templates();
            templateCache = new Cache<>(live, expires());
            return live;
        } catch (CdssClient.RemoteFailure e) {
            return cache == null ? snapshot : cache.value();
        }
    }

    private long expires() { return clock.millis() + Duration.ofMinutes(15).toMillis(); }

    ObjectNode cdssContent(Schema schema, Records.Material material, String summary, Map<String, String> fields) {
        ObjectNode layout = schema.layout().deepCopy();
        String summaryKey = schema.summaryKey();
        if (material.pdf() && !List.of("abstract", "summary").contains(summaryKey)) {
            summaryKey = "abstract";
            ensureSection((ArrayNode) layout.path("catalog"), schema.fields(), summaryKey, "摘要");
        }
        var content = layout.putObject("content");
        for (Field field : fields(layout)) {
            String value = switch (field.id()) {
                case "guideType" -> schema.category().name();
                case "guideAuthor" -> material.authors();
                case "publishTime" -> material.published();
                case "organ" -> material.publisher();
                case "laiyuan" -> material.sourceUrl() == null ? material.sourceName() : material.sourceUrl();
                default -> null;
            };
            if (!material.pdf() && !trim(fields.get(field.id())).isBlank()) value = fields.get(field.id());
            if (field.id().equals(summaryKey)) value = summary;
            if (trim(value).isBlank()) continue;
            if (field.id().equals("laiyuan") && value.equals(material.sourceUrl())) {
                String safeUrl = HtmlUtils.htmlEscape(httpUri(value).toString());
                content.put(field.id(), "<p><a href=\"" + safeUrl + "\" target=\"_blank\" rel=\"noopener\">"
                        + safeUrl + "</a></p>");
            } else {
                content.put(field.id(), field.property() ? value : "<p>" + HtmlUtils.htmlEscape(value)
                        .replace("\r\n", "\n").replace("\n", "<br>") + "</p>");
            }
        }
        return layout;
    }
}
