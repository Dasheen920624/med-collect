package com.medcollect;

import static com.medcollect.BusinessKnowledge.Operation.*;
import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class SyncReviews {
    private static final String COLUMNS = "id, knowledge_id, remote_id, version_no, operation, reviewer, approved_at, synced_at";

    private record Row(String reviewId, int draftRevision, String planJson, String reviewer, Long approvedAt) {}
    record Approved(String id, String reviewer, long approvedAt, BusinessKnowledge.Plan plan) {}
    record Preview(String id, boolean approved, BusinessKnowledge.Operation operation, String doi, boolean mayResetBusinessReview,
                   BusinessKnowledge.Candidate target, List<BusinessKnowledge.Candidate> candidates,
                   List<BusinessKnowledge.Difference> differences) {}
    record Version(String id, String knowledgeId, String remoteId, int versionNo, BusinessKnowledge.Operation operation,
                   String reviewer, long approvedAt, long syncedAt) {}
    private record Recorded(String beforeJson, String afterJson, String changesJson) {}
    record VersionDetail(JsonNode before, JsonNode after, List<BusinessKnowledge.Difference> differences) {}

    private final Store store;
    private final CdssClient cdss;
    private final BusinessKnowledge business;
    private final KnowledgeWorkflow workflow;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    SyncReviews(Store store, CdssClient cdss, BusinessKnowledge business, KnowledgeWorkflow workflow, ObjectMapper json,
                TransactionTemplate transactions) {
        this.store = store;
        this.cdss = cdss;
        this.business = business;
        this.workflow = workflow;
        this.json = json;
        this.transactions = transactions;
    }

    Preview prepare(String id, int revision, String targetId, String actor) {
        Knowledge draft = store.knowledge(id);
        requireReviewable(draft, revision);
        if (!cdss.configured()) throw conflict("请配置 4.23 业务账号后查重");
        BusinessKnowledge.Plan plan;
        try {
            plan = business.plan(draft, store.material(draft.materialId()), targetId);
        } catch (CdssClient.RemoteFailure e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
        return transactions.execute(tx -> {
            lockDraft(id);
            Knowledge current = store.knowledge(id);
            requireReviewable(current, revision);
            int nextRevision = revision + 1;
            changed(store.db().sql("""
                    UPDATE mc_knowledge SET status = 'PENDING', revision = :next, sync_error = NULL, updated_at = :now
                    WHERE id = :id AND revision = :revision
                    """).param("id", id).param("next", nextRevision).param("revision", revision).param("now", store.now()).update());
            store.db().sql("DELETE FROM mc_sync_review WHERE knowledge_id = :id").param("id", id).update();
            String review = id();
            store.db().sql("""
                    INSERT INTO mc_sync_review (knowledge_id, review_id, draft_revision, plan_json)
                    VALUES (:id, :review, :revision, :plan)
                    """).param("id", id).param("review", review).param("revision", nextRevision)
                    .param("plan", write(plan), Types.CLOB).update();
            store.event("KNOWLEDGE", id, "SYNC_REVIEWED", actor, plan.target() == null ? plan.operation().name() : plan.target().id());
            return view(review, false, plan);
        });
    }

    @Transactional
    KnowledgeWorkflow.Detail decision(String id, Decision input, String actor) {
        lockDraft(id);
        if (input.approved() && cdss.configured()) {
            Row review = review(id).orElseThrow(() -> conflict("请先检查 4.23 重复与差异"));
            if (!review.reviewId().equals(input.reviewId()) || review.draftRevision() != input.revision()
                    || review.approvedAt() != null || readPlan(review.planJson()).operation() == CHOOSE) {
                throw conflict("请核对当前查重结果并明确选择业务记录后再审批");
            }
            changed(store.db().sql("""
                    UPDATE mc_sync_review SET reviewer = :actor, approved_at = :now
                    WHERE knowledge_id = :id AND review_id = :review AND approved_at IS NULL
                    """).param("id", id).param("review", review.reviewId()).param("actor", actor).param("now", store.now()).update());
        }
        return workflow.decision(id, input, actor);
    }

    Preview preview(Knowledge draft) {
        return review(draft.id()).filter(row -> switch (draft.status()) {
            case PENDING -> row.draftRevision() == draft.revision();
            case APPROVED, SYNCING, SYNC_FAILED, SYNCED -> row.approvedAt() != null;
            default -> false;
        }).map(row -> view(row.reviewId(), row.approvedAt() != null, readPlan(row.planJson()))).orElse(null);
    }

    Optional<Approved> approved(String draftId) {
        return review(draftId).filter(row -> row.approvedAt() != null)
                .map(row -> new Approved(row.reviewId(), row.reviewer(), row.approvedAt(), readPlan(row.planJson())));
    }

    @Transactional
    KnowledgeWorkflow.Detail revise(String originalId, int revision, String actor) {
        lockDraft(originalId);
        Knowledge original = store.knowledge(originalId);
        if (original.status() != KnowledgeStatus.SYNCED || original.remoteId() == null || original.revision() != revision) {
            throw conflict("仅当前已同步且有业务主键的知识可发起新版本");
        }
        String latest = store.db().sql("""
                SELECT id FROM mc_knowledge WHERE remote_id = :target AND status = 'SYNCED'
                ORDER BY updated_at DESC, id DESC FETCH FIRST 1 ROWS ONLY
                """).param("target", original.remoteId()).query(String.class).single();
        if (!originalId.equals(latest)) lockDraft(latest);
        var existing = store.db().sql("""
                SELECT id FROM mc_knowledge WHERE update_target_id = :target AND status <> 'SYNCED'
                ORDER BY created_at DESC, id DESC FETCH FIRST 1 ROWS ONLY
                """).param("target", original.remoteId())
                .query(String.class).optional();
        if (existing.isPresent()) return workflow.detail(existing.get());
        String draft = id();
        changed(store.db().sql("""
                INSERT INTO mc_knowledge (id, material_id, title, type_code, knowledge_class, summary, template_json,
                    fields_json, relations_json, status, revision, model_name, created_at, updated_at, update_target_id)
                SELECT :draft, material_id, title, type_code, knowledge_class, summary, template_json, fields_json,
                    relations_json, 'DRAFT', 1, model_name, :now, :now, remote_id FROM mc_knowledge WHERE id = :id
                """).param("draft", draft).param("id", latest).param("now", store.now()).update());
        store.event("KNOWLEDGE", draft, "REVISION_CREATED", actor, original.remoteId());
        return workflow.detail(draft);
    }

    Page<Version> versions(String knowledgeId, int page, int size) {
        String remoteId = historyTarget(store.knowledge(knowledgeId));
        if (remoteId == null) return new Page<>(List.of(), 0, page, size);
        return store.page(COLUMNS, "FROM mc_sync_version WHERE remote_id = :remote", "version_no DESC",
                Map.of("remote", remoteId), page, size, Version.class);
    }

    VersionDetail version(String knowledgeId, String versionId) {
        String remoteId = historyTarget(store.knowledge(knowledgeId));
        if (remoteId == null) throw missing("版本记录不存在");
        Recorded record = store.db().sql("""
                SELECT before_json, after_json, changes_json FROM mc_sync_version WHERE id = :id AND remote_id = :remote
                """).param("id", versionId).param("remote", remoteId).query(Recorded.class).optional()
                .orElseThrow(() -> missing("版本记录不存在"));
        try {
            return new VersionDetail(record.beforeJson() == null ? null : json.readTree(record.beforeJson()),
                    json.readTree(record.afterJson()), json.readValue(record.changesJson(), new TypeReference<>() {}));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid sync version payload", e);
        }
    }

    void recordVersion(String knowledgeId, String remoteId, Approved approval, JsonNode actual) {
        int sequence = store.db().sql("SELECT COALESCE(MAX(version_no), 0) + 1 FROM mc_sync_version WHERE remote_id = :remote")
                .param("remote", remoteId).query(Integer.class).single();
        var plan = approval.plan();
        store.db().sql("""
                INSERT INTO mc_sync_version (id, knowledge_id, remote_id, version_no, operation,
                    before_json, after_json, changes_json, reviewer, approved_at, synced_at)
                VALUES (:id, :knowledge, :remote, :version, :operation, :before, :after, :changes, :reviewer, :approved, :now)
                """).param("id", approval.id()).param("knowledge", knowledgeId).param("remote", remoteId)
                .param("version", sequence).param("operation", plan.operation().name())
                .param("before", plan.before() == null ? null : write(plan.before()), Types.CLOB)
                .param("after", write(actual), Types.CLOB).param("changes", write(plan.differences()), Types.CLOB)
                .param("reviewer", approval.reviewer()).param("approved", approval.approvedAt()).param("now", store.now()).update();
    }

    boolean claimWriter(String token, long duration) {
        long now = store.now();
        return store.db().sql("""
                UPDATE mc_sync_writer SET lease_token = :token, lease_until = :until
                WHERE id = 1 AND (lease_token IS NULL OR lease_until < :now)
                """).param("token", token).param("until", now + duration).param("now", now).update() == 1;
    }

    void releaseWriter(String token) {
        store.db().sql("UPDATE mc_sync_writer SET lease_token = NULL, lease_until = NULL WHERE id = 1 AND lease_token = :token")
                .param("token", token).update();
    }

    boolean incompleteWrite(String draftId, String remoteId) {
        return store.db().sql("""
                SELECT COUNT(*) FROM mc_knowledge WHERE remote_id = :remote AND id <> :id
                AND status IN ('SYNC_FAILED', 'SYNCING') AND sync_phase IS NOT NULL
                """).param("remote", remoteId).param("id", draftId).query(Long.class).single() > 0;
    }

    private void requireReviewable(Knowledge draft, int revision) {
        if (draft.revision() != revision || draft.remoteId() != null || draft.syncPhase() != null
                || !List.of(KnowledgeStatus.PENDING, KnowledgeStatus.APPROVED, KnowledgeStatus.SYNC_FAILED).contains(draft.status())) {
            throw conflict("当前稿件状态或修订号已变化，请刷新后重试");
        }
    }

    private void lockDraft(String id) {
        store.db().sql("SELECT id FROM mc_knowledge WHERE id = :id FOR UPDATE").param("id", id).query(String.class).optional()
                .orElseThrow(() -> missing("知识稿件不存在"));
    }

    private String historyTarget(Knowledge draft) {
        if (draft.remoteId() != null) return draft.remoteId();
        if (draft.updateTargetId() != null) return draft.updateTargetId();
        var preview = preview(draft);
        return preview == null || preview.target() == null ? null : preview.target().id();
    }

    private Optional<Row> review(String id) {
        return store.db().sql("SELECT review_id, draft_revision, plan_json, reviewer, approved_at FROM mc_sync_review WHERE knowledge_id = :id")
                .param("id", id).query(Row.class).optional();
    }

    private Preview view(String id, boolean approved, BusinessKnowledge.Plan plan) {
        boolean mayReset = plan.operation() == UPDATE && (plan.before().path("isStart").asInt() != 0
                || plan.before().path("auditStatus").asInt() != 0 || plan.before().path("releaseStatus").asInt() != 0);
        return new Preview(id, approved, plan.operation(), plan.doi(), mayReset, plan.target(), plan.candidates(), plan.differences());
    }

    private BusinessKnowledge.Plan readPlan(String raw) {
        try { return json.readValue(raw, BusinessKnowledge.Plan.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid stored sync review", e); }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException("Invalid sync payload", e); }
    }
}
