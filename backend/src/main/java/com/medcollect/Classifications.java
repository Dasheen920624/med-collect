package com.medcollect;

import static com.medcollect.Support.*;

import java.sql.Types;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class Classifications {
    enum Group { SUBJECT, DOMAIN, MATERIAL }
    record Node(String id, Group groupCode, String name, String parentId, String businessCode,
                int displayOrder, boolean enabled, int revision, long materialCount, long knowledgeCount, String remoteKey) {}
    record Input(@NotNull Group groupCode, @NotBlank @Size(max = 120) String name,
                 @Size(max = 36) String parentId, @Size(max = 80) String businessCode,
                 @Min(0) @Max(999999) int displayOrder, boolean enabled, Integer revision) {}
    record Assignment(@NotNull Group groupCode,
                      @NotNull @Size(min = 1, max = 50) List<@NotBlank @Size(max = 36) String> materialIds,
                      @NotNull @Size(max = 50) List<@NotBlank @Size(max = 36) String> classIds) {}

    private static final String NODE_QUERY = """
            SELECT c.*,
                (SELECT COUNT(DISTINCT a.material_id) FROM mc_material_class a JOIN mc_classification sub ON sub.id = a.class_id
                  JOIN mc_material m ON m.id = a.material_id WHERE m.archived_at IS NULL AND (sub.id = c.id OR sub.parent_id = c.id)) material_count,
                (SELECT COUNT(DISTINCT k.id) FROM mc_knowledge k JOIN mc_material_class a ON a.material_id = k.material_id
                  JOIN mc_material m ON m.id = k.material_id
                  JOIN mc_classification sub ON sub.id = a.class_id
                  WHERE m.archived_at IS NULL AND (sub.id = c.id OR sub.parent_id = c.id)) knowledge_count
            FROM mc_classification c
            """;
    private final Store store;

    Classifications(Store store) { this.store = store; }

    List<Node> nodes() {
        return store.db().sql(NODE_QUERY + " ORDER BY c.group_code, c.display_order, c.name, c.id").query(Node.class).list();
    }

    List<String> materialClasses(String id) {
        return store.db().sql("SELECT class_id FROM mc_material_class WHERE material_id = :id ORDER BY class_id")
                .param("id", id).query(String.class).list();
    }

    static String subjectScope(String materialColumn, String subjectId, java.util.Map<String, Object> params) {
        if (trim(subjectId).isBlank()) return "";
        String match = "SELECT 1 FROM mc_material_class a JOIN mc_classification c ON c.id = a.class_id"
                + " WHERE a.material_id = " + materialColumn + " AND c.group_code = 'SUBJECT'";
        if ("unassigned".equals(subjectId)) return " AND NOT EXISTS (" + match + ")";
        params.put("subjectId", subjectId);
        return " AND EXISTS (" + match + " AND (c.id = :subjectId OR c.parent_id = :subjectId))";
    }

    static String taskScope(String subjectId, java.util.Map<String, Object> params) {
        if (trim(subjectId).isBlank()) return "";
        return " AND EXISTS (SELECT 1 FROM mc_run sr JOIN mc_run_result rr ON rr.run_id = sr.id"
                + " WHERE sr.task_id = t.id AND rr.material_id IS NOT NULL"
                + subjectScope("rr.material_id", subjectId, params) + ")";
    }

    private Node node(String id) {
        return store.db().sql(NODE_QUERY + " WHERE c.id = :id").param("id", id).query(Node.class).optional()
                .orElseThrow(() -> missing("分类不存在"));
    }

    @Transactional
    Node save(String id, Input input, String actor) {
        lock();
        Node current = id == null ? null : node(id);
        if (current != null && current.remoteKey() != null) throw bad("此分类由 4.23 同步，请在业务系统维护");
        String parentId = trim(input.parentId()).isEmpty() ? null : input.parentId().strip();
        if (input.groupCode() == Group.MATERIAL && parentId != null) throw bad("资料类型使用一级目录");
        if (current != null && current.groupCode() != input.groupCode()) throw bad("既有分类的维度不可修改");
        if (current != null && !Objects.equals(current.parentId(), parentId)
                && (current.materialCount() > 0 || children(id) > 0)) {
            throw conflict("已被引用或已有子级的分类不可移动到其他上级");
        }
        if (parentId != null) {
            Node parent = node(parentId);
            if (parent.id().equals(id) || parent.groupCode() != input.groupCode() || parent.parentId() != null) {
                throw bad("请选择同一维度中的一级目录");
            }
            if (!parent.enabled() && (current == null || !parentId.equals(current.parentId()))) throw bad("上级目录已停用");
        }
        String key = id == null ? id() : id;
        String name = input.name().strip();
        String sql = current == null ? """
                INSERT INTO mc_classification (id, group_code, parent_id, parent_key, name, name_key,
                  business_code, display_order, enabled, revision)
                VALUES (:id, :group, :parent, :parentKey, :name, :nameKey, :code, :position, :enabled, 1)
                """ : """
                UPDATE mc_classification SET parent_id = :parent, parent_key = :parentKey, name = :name,
                  name_key = :nameKey, business_code = :code, display_order = :position, enabled = :enabled,
                  revision = revision + 1 WHERE id = :id AND revision = :revision
                """;
        try {
            changed(store.db().sql(sql).param("id", key).param("group", input.groupCode().name())
                    .param("parent", parentId, Types.VARCHAR).param("parentKey", parentId == null ? "ROOT" : parentId)
                    .param("name", name).param("nameKey", name.toLowerCase(Locale.ROOT))
                    .param("code", trim(input.businessCode()), Types.VARCHAR).param("position", input.displayOrder())
                    .param("enabled", input.enabled() ? 1 : 0).param("revision", input.revision(), Types.NUMERIC).update());
        } catch (DuplicateKeyException e) {
            throw conflict("同级分类名称已存在");
        }
        store.event("CLASSIFICATION", key, current == null ? "CREATED" : "UPDATED", actor, name);
        return node(key);
    }

    record Move(@NotBlank String peerId, boolean up, int revision) {}

    @Transactional
    void move(String id, Move input, String actor) {
        lock();
        Node current = node(id);
        if (current.remoteKey() != null) throw bad("业务目录按 4.23 的顺序显示");
        var siblings = new java.util.ArrayList<>(nodes().stream().filter(n -> n.remoteKey() == null && n.groupCode() == current.groupCode()
                && Objects.equals(n.parentId(), current.parentId())).toList());
        int index = java.util.stream.IntStream.range(0, siblings.size()).filter(i -> siblings.get(i).id().equals(id))
                .findFirst().orElseThrow(() -> conflict("目录已变化，请刷新后重试"));
        int next = index + (input.up() ? -1 : 1);
        if (current.revision() != input.revision() || next < 0 || next >= siblings.size()
                || !siblings.get(next).id().equals(input.peerId())) throw conflict("目录顺序已变化，请刷新后重试");
        java.util.Collections.swap(siblings, index, next);
        for (int i = 0; i < siblings.size(); i++) {
            Node sibling = siblings.get(i);
            int position = (i + 1) * 10;
            if (sibling.displayOrder() == position) continue;
            changed(store.db().sql("UPDATE mc_classification SET display_order = :position, revision = revision + 1"
                    + " WHERE id = :id AND revision = :revision").param("position", position)
                    .param("id", sibling.id()).param("revision", sibling.revision()).update());
        }
        store.event("CLASSIFICATION", id, "UPDATED", actor, input.up() ? "上移" : "下移");
    }

    @Transactional
    void assign(Assignment input, String actor) {
        lock();
        var choices = new LinkedHashSet<Node>();
        for (String id : new LinkedHashSet<>(input.classIds())) {
            Node selected = node(id);
            if (selected.groupCode() != input.groupCode() || !selected.enabled()) throw bad("请选择已启用的同类目录");
            choices.add(selected);
            if (input.groupCode() == Group.SUBJECT && selected.parentId() != null) choices.add(node(selected.parentId()));
            if (selected.parentId() != null && !node(selected.parentId()).enabled()) throw bad("分类的上级目录已停用");
        }
        if (input.groupCode() == Group.MATERIAL && choices.size() > 1) throw bad("每份原文只能选择一种资料类型");
        for (String materialId : new LinkedHashSet<>(input.materialIds())) {
            store.lockActiveMaterial(materialId);
            store.db().sql("""
                    DELETE FROM mc_material_class WHERE material_id = :id AND class_id IN
                      (SELECT id FROM mc_classification WHERE group_code = :group)
                    """).param("id", materialId).param("group", input.groupCode().name()).update();
            for (Node choice : choices) {
                store.db().sql("INSERT INTO mc_material_class (material_id, class_id) VALUES (:id, :class)")
                        .param("id", materialId).param("class", choice.id()).update();
            }
            store.event("MATERIAL", materialId, "CLASSIFIED", actor,
                    limit(String.join("、", choices.stream().map(Node::name).toList()), 1000));
        }
    }

    private Long children(String id) {
        return store.db().sql("SELECT COUNT(*) FROM mc_classification WHERE parent_id = :id")
                .param("id", id).query(Long.class).single();
    }

    void lock() {
        store.db().sql("SELECT setting_key FROM mc_settings WHERE setting_key = 'collection_classifications' FOR UPDATE")
                .query(String.class).single();
    }
}
