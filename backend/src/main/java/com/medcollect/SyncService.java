package com.medcollect;

import static com.medcollect.BusinessKnowledge.marker;
import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.sql.Types;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executor;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
class SyncService {
    private static final Logger log = LoggerFactory.getLogger(SyncService.class);
    private static final long LEASE_MILLIS = Duration.ofMinutes(30).toMillis();
    private final Store store;
    private final KnowledgeWorkflow workflow;
    private final KnowledgeTemplates templates;
    private final CdssClient cdss;
    private final BusinessKnowledge business;
    private final SyncReviews reviews;
    private final Executor workers;
    private final TransactionTemplate transactions;

    SyncService(Store store, KnowledgeWorkflow workflow, KnowledgeTemplates templates, CdssClient cdss,
                BusinessKnowledge business, SyncReviews reviews,
                @Qualifier("workExecutor") Executor workers, TransactionTemplate transactions) {
        this.store = store;
        this.workflow = workflow;
        this.templates = templates;
        this.cdss = cdss;
        this.business = business;
        this.reviews = reviews;
        this.workers = workers;
        this.transactions = transactions;
    }

    KnowledgeWorkflow.Detail start(String id, int revision, String actor) {
        if (!cdss.writeEnabled()) throw conflict("服务端尚未启用 4.23 同步");
        Knowledge current = store.knowledge(id);
        if (current.remoteId() == null && current.syncPhase() == null && reviews.approved(id).isEmpty()) {
            throw conflict("同步前须检查 4.23 重复与差异，并完成当前稿件的人工审核");
        }
        String token = id();
        transactions.executeWithoutResult(tx -> {
            if (!reviews.claimWriter(token, LEASE_MILLIS)) throw conflict("其他知识正在同步，请稍后重试");
            long now = store.now();
            changed(store.db().sql("""
                    UPDATE mc_knowledge SET status = 'SYNCING', sync_lease = :lease, sync_token = :token,
                      sync_error = NULL, revision = revision + 1, updated_at = :now
                    WHERE id = :id AND revision = :revision AND (
                      status IN ('APPROVED', 'SYNC_FAILED') OR (status = 'SYNCING' AND sync_lease < :now)
                    )
                    """).param("id", id).param("revision", revision).param("lease", now + LEASE_MILLIS)
                    .param("now", now).param("token", token).update());
            store.event("KNOWLEDGE", id, "SYNC_STARTED", actor, marker(id));
        });
        try {
            workers.execute(() -> synchronize(id, token, actor));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            finish(id, token, actor, KnowledgeStatus.SYNC_FAILED, "同步执行队列已满");
        }
        return workflow.detail(id);
    }

    private void synchronize(String id, String token, String actor) {
        try {
            Knowledge knowledge = owned(id, token);
            Material material = store.material(knowledge.materialId());
            var schema = workflow.schema(knowledge);
            var approval = reviews.approved(id).orElse(null);
            if (approval != null && approval.plan().target() != null) {
                syncMatched(knowledge, material, schema, approval, token, actor);
                return;
            }
            String remoteId = knowledge.remoteId();
            if (remoteId == null) remoteId = saveRecord(knowledge, material, schema, approval != null, token, actor);
            if (material.pdf()) syncPdf(owned(id, token), material, remoteId, token, actor);
            cdss.verifyRecord(remoteId, schema.category().type(), marker(id));
            ObjectNode actual = approval == null ? null : business.snapshot(remoteId, schema.category());
            complete(id, token, actor, remoteId, approval, actual, false);
        } catch (BusinessKnowledge.ReviewChanged e) {
            redoReview(id, token, actor, e.getMessage());
        } catch (CdssClient.RemoteFailure e) {
            finish(id, token, actor, KnowledgeStatus.SYNC_FAILED, e.getMessage());
        } catch (ResponseStatusException e) {
            finish(id, token, actor, KnowledgeStatus.SYNC_FAILED, e.getReason());
        } catch (RuntimeException e) {
            log.warn("Sync failed for knowledge {}", id, e);
            finish(id, token, actor, KnowledgeStatus.SYNC_FAILED, "同步中断，请核对同步明细后重试");
        } finally {
            try { reviews.releaseWriter(token); }
            catch (RuntimeException e) { log.warn("Could not release the knowledge sync lease", e); }
        }
    }

    private String saveRecord(Knowledge knowledge, Material material, KnowledgeTemplates.Schema schema,
                              boolean reviewed, String token, String actor) {
        var category = schema.category();
        String remoteId;
        if ("POSTING".equals(knowledge.syncPhase())) {
            remoteId = cdss.findCreated(category.type(), category.childType(), knowledge.title(), marker(knowledge.id()));
            if (remoteId == null) {
                throw new CdssClient.RemoteFailure("上次新增结果待核实。请在 4.23 按标题及备注 "
                        + marker(knowledge.id()) + " 核对后重试");
            }
        } else {
            templates.validateClass(category, knowledge.knowledgeClass(), true);
            if (reviewed) business.verifyNoMatch(knowledge, material);
            Map<String, String> form = business.createForm(knowledge, material, schema);
            cdss.prepareWrite();
            checkpoint(knowledge.id(), token, actor, "POSTING", null, null);
            try {
                remoteId = cdss.create(form);
            } catch (CdssClient.Rejected e) {
                checkpoint(knowledge.id(), token, actor, null, null, null);
                throw e;
            }
        }
        checkpoint(knowledge.id(), token, actor, "CREATED", remoteId, null);
        return remoteId;
    }

    private void syncMatched(Knowledge knowledge, Material material, KnowledgeTemplates.Schema schema,
                             SyncReviews.Approved approval, String token, String actor) {
        var plan = approval.plan();
        String id = knowledge.id();
        String remoteId = plan.target().id();
        if (reviews.incompleteWrite(id, remoteId)) {
            throw new CdssClient.RemoteFailure("该业务记录有未完成的同步，请先核对并恢复该操作");
        }
        ObjectNode current = business.snapshot(remoteId, schema.category());
        if (knowledge.syncPhase() == null && !plan.baseline().equals(BusinessKnowledge.fingerprint(current))) {
            throw new BusinessKnowledge.ReviewChanged("4.23 原业务记录在审核后发生变化，请重新对比并审核");
        }
        if (plan.operation() == BusinessKnowledge.Operation.SKIP) {
            complete(id, token, actor, remoteId, approval, null, true);
            return;
        }
        if (knowledge.syncPhase() == null || "UPDATE_POSTING".equals(knowledge.syncPhase())) {
            if (!business.matchesUpdate(plan, current, id, false)) {
                if (!plan.baseline().equals(BusinessKnowledge.fingerprint(current))) {
                    if (!current.path("remark").asText("").contains(BusinessKnowledge.updateMarker(id))) {
                        throw new BusinessKnowledge.ReviewChanged("4.23 原业务记录已变化，请重新对比并审核");
                    }
                    throw new CdssClient.RemoteFailure("4.23 已记录本次更新标识，当前内容与审核稿不一致，请核对业务详情");
                }
                if (current.path("lockStatus").asInt() != 0) throw new CdssClient.RemoteFailure("4.23 业务知识处于锁定状态");
                templates.validateClass(schema.category(), knowledge.knowledgeClass(), true);
                cdss.prepareWrite();
                checkpoint(id, token, actor, "UPDATE_POSTING", remoteId, null);
                cdss.update(remoteId, plan.form());
            }
            checkpoint(id, token, actor, "UPDATED", remoteId, null);
        }
        current = business.snapshot(remoteId, schema.category());
        if (!business.matchesUpdate(plan, current, id, plan.uploadPdf())) {
            throw new CdssClient.RemoteFailure("4.23 当前内容与已审核版本不一致，请核对业务详情");
        }
        if (plan.uploadPdf()) {
            syncPdf(owned(id, token), material, remoteId, token, actor);
            current = business.snapshot(remoteId, schema.category());
            if (!business.matchesUpdate(plan, current, id, true)) {
                throw new CdssClient.RemoteFailure("4.23 附件写入期间业务正文发生变化，请核对业务详情");
            }
            String references = business.restoreReferences(plan, current);
            if (references != null) {
                cdss.update(remoteId, Map.of("name", plan.form().get("name"), "type", plan.form().get("type"), "content", references));
                current = business.snapshot(remoteId, schema.category());
                if (business.restoreReferences(plan, current) != null || !business.matchesUpdate(plan, current, id, true)) {
                    throw new CdssClient.RemoteFailure("4.23 更新回读时未保留已审核正文或参考文献");
                }
            }
        }
        complete(id, token, actor, remoteId, approval, current, false);
    }

    private void syncPdf(Knowledge knowledge, Material material, String remoteId, String token, String actor) {
        if ("ATTACHED".equals(knowledge.syncPhase())) return;
        String attachment = knowledge.attachmentUrl();
        if (attachment == null) {
            byte[] original = store.bytes(material);
            attachment = cdss.uploadPdf(material.fileName(), original);
            checkpoint(knowledge.id(), token, actor, "UPLOADED", remoteId, attachment);
        }
        if (!cdss.hasAttachment(remoteId, attachment)) {
            cdss.bindAttachment(remoteId, attachment, material.fileName(), knowledge.id().replace("-", ""));
        }
        if (!cdss.hasAttachment(remoteId, attachment)) {
            throw new CdssClient.RemoteFailure("4.23 已响应附件绑定，复查时尚未找到附件");
        }
        checkpoint(knowledge.id(), token, actor, "ATTACHED", remoteId, attachment);
    }

    KnowledgeWorkflow.Detail resetUncertain(String id, int revision, String note, String actor) {
        String reason = requireText(note, "请记录已在 4.23 核对的结果");
        Knowledge knowledge = store.knowledge(id);
        if (knowledge.status() != KnowledgeStatus.SYNC_FAILED || knowledge.remoteId() != null
                || !"POSTING".equals(knowledge.syncPhase()) || knowledge.revision() != revision) {
            throw conflict("仅待核实且无业务主键的新增可重新排队");
        }
        var category = workflow.schema(knowledge).category();
        if (cdss.findCreated(category.type(), category.childType(), knowledge.title(), marker(id)) != null) {
            throw conflict("已在业务系统找到对应记录，请直接重试同步以读取回执");
        }
        transactions.executeWithoutResult(tx -> {
            changed(store.db().sql("""
                    UPDATE mc_knowledge SET sync_phase = NULL, sync_error = NULL, revision = revision + 1, updated_at = :now
                    WHERE id = :id AND revision = :revision AND status = 'SYNC_FAILED' AND sync_phase = 'POSTING'
                    AND remote_id IS NULL
                    """).param("id", id).param("revision", revision).param("now", store.now()).update());
            store.event("KNOWLEDGE", id, "SYNC_RESET", actor, reason);
        });
        return workflow.detail(id);
    }

    private Knowledge owned(String id, String token) {
        var knowledge = store.knowledge(id);
        if (!token.equals(knowledge.syncToken())) throw conflict("同步任务状态已变化");
        return knowledge;
    }

    private void checkpoint(String id, String token, String actor, String phase, String remote, String attachment) {
        transactions.executeWithoutResult(tx -> {
            changed(store.db().sql("""
                    UPDATE mc_knowledge SET sync_phase = :phase, remote_id = :remote, attachment_url = :attachment,
                      sync_lease = :lease, updated_at = :now WHERE id = :id AND status = 'SYNCING' AND sync_token = :token
                    """).param("phase", phase, Types.VARCHAR).param("remote", remote, Types.VARCHAR)
                    .param("attachment", attachment, Types.VARCHAR).param("lease", store.now() + LEASE_MILLIS)
                    .param("now", store.now()).param("id", id).param("token", token).update());
            store.event("KNOWLEDGE", id, phase == null ? "SYNC_REJECTED" : "SYNC_" + phase,
                    actor, attachment == null ? remote : attachment);
        });
    }

    private void finish(String id, String token, String actor, KnowledgeStatus status, String message) {
        transactions.executeWithoutResult(tx -> {
            int updated = store.db().sql("""
                    UPDATE mc_knowledge SET status = :status, sync_lease = NULL, sync_token = NULL, sync_error = :error,
                      updated_at = :now, revision = revision + 1
                    WHERE id = :id AND status = 'SYNCING' AND sync_token = :token
                    """).param("status", status.name())
                    .param("error", status == KnowledgeStatus.SYNC_FAILED ? limit(message, 1000) : null, Types.VARCHAR)
                    .param("now", store.now()).param("id", id).param("token", token).update();
            if (updated == 1) store.event("KNOWLEDGE", id, status.name(), actor, message);
            reviews.releaseWriter(token);
        });
    }

    private void complete(String id, String token, String actor, String remoteId, SyncReviews.Approved approval,
                          ObjectNode actual, boolean skipped) {
        transactions.executeWithoutResult(tx -> {
            changed(store.db().sql("""
                    UPDATE mc_knowledge SET status = 'SYNCED', remote_id = :remote,
                      sync_phase = CASE WHEN :skip = 1 THEN 'SKIPPED' ELSE sync_phase END,
                      sync_lease = NULL, sync_token = NULL, sync_error = NULL, revision = revision + 1, updated_at = :now
                    WHERE id = :id AND status = 'SYNCING' AND sync_token = :token
                    """).param("id", id).param("remote", remoteId).param("skip", skipped ? 1 : 0).param("now", store.now())
                    .param("token", token).update());
            if (approval != null && !skipped) reviews.recordVersion(id, remoteId, approval, actual);
            store.event("KNOWLEDGE", id, skipped ? "SYNC_SKIPPED" : "SYNCED", actor, remoteId);
            reviews.releaseWriter(token);
        });
    }

    private void redoReview(String id, String token, String actor, String message) {
        transactions.executeWithoutResult(tx -> {
            changed(store.db().sql("""
                    UPDATE mc_knowledge SET status = 'PENDING', sync_phase = NULL, remote_id = NULL,
                      sync_lease = NULL, sync_token = NULL, sync_error = :error, revision = revision + 1, updated_at = :now
                    WHERE id = :id AND status = 'SYNCING' AND sync_token = :token
                    """).param("id", id).param("error", message).param("now", store.now()).param("token", token).update());
            store.event("KNOWLEDGE", id, "SYNC_REVIEW_STALE", actor, message);
            reviews.releaseWriter(token);
        });
    }
}
