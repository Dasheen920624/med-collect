package com.medcollect;

import static com.medcollect.BusinessKnowledge.marker;
import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.sql.Types;
import java.time.Duration;
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
    private final SyncOrigins origins;
    private final Executor workers;
    private final TransactionTemplate transactions;

    SyncService(Store store, KnowledgeWorkflow workflow, KnowledgeTemplates templates, CdssClient cdss,
                BusinessKnowledge business, SyncReviews reviews,
                @Qualifier("workExecutor") Executor workers, TransactionTemplate transactions, SyncOrigins origins) {
        this.store = store;
        this.workflow = workflow;
        this.templates = templates;
        this.cdss = cdss;
        this.business = business;
        this.reviews = reviews;
        this.origins = origins;
        this.workers = workers;
        this.transactions = transactions;
    }

    KnowledgeWorkflow.Detail start(String id, int revision, String actor) {
        if (!cdss.writeEnabled()) throw conflict("服务端尚未启用 4.23 同步");
        Knowledge current = store.knowledge(id);
        workflow.requireSource(store.material(current.materialId()),current.typeCode());
        requireWriteAuthority(current, reviews.approved(id).orElse(null));
        String token = id();
        transactions.executeWithoutResult(tx -> {
            store.lockActiveMaterial(store.knowledge(id).materialId());
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
            Material material = store.requireActive(store.material(knowledge.materialId()));
            var schema = workflow.schema(knowledge);
            var approval = reviews.approved(id).orElse(null);
            requireWriteAuthority(knowledge, approval);
            if (!templates.liveDestination(schema.category(), knowledge.knowledgeClass())) {
                categoryChanged(knowledge);
            }
            if (approval != null && approval.plan().target() != null) {
                syncMatched(knowledge, material, schema, approval, token, actor);
                return;
            }
            String remoteId = knowledge.remoteId();
            if (remoteId == null) remoteId = saveRecord(knowledge, material, schema, approval, token, actor);
            else if (!business.targetCategory(business.snapshot(remoteId,schema.category()), knowledge, schema.category())) categoryChanged(knowledge);
            if (material.pdf()) syncPdf(owned(id, token), material, remoteId, token, actor);
            ObjectNode actual = business.snapshot(remoteId, schema.category());
            business.verifyCreated(knowledge, material, actual, true);
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

    private void categoryChanged(Knowledge knowledge) {
        String message = "4.23 业务入口或知识类别已失效，请核对业务分类";
        if (knowledge.syncPhase() != null) throw new CdssClient.RemoteFailure(message + "及已有写入回执");
        throw new BusinessKnowledge.ReviewChanged(message + "并重新审核");
    }

    private void requireWriteAuthority(Knowledge draft, SyncReviews.Approved approval) {
        if (draft.status() == KnowledgeStatus.REFERENCE_ONLY) throw conflict(SyncOrigins.PROTECTED);
        var plan = approval == null ? null : approval.plan();
        if (plan != null && plan.target() != null) {
            if (plan.operation() == BusinessKnowledge.Operation.SKIP && draft.syncPhase() == null && draft.remoteId() == null) return;
            if (plan.operation() != BusinessKnowledge.Operation.UPDATE) throw conflict(SyncOrigins.PROTECTED);
            origins.requireOwned(plan.target().id());
            if (draft.remoteId() != null && !plan.target().id().equals(draft.remoteId())) throw conflict("同步目标与审核目标不符");
            return;
        }
        if (plan != null && plan.operation() != BusinessKnowledge.Operation.CREATE) throw conflict("请重新核对同步目标");
        if (draft.remoteId() != null) {
            if (!origins.createdBy(draft.remoteId(), draft.id())) throw conflict(SyncOrigins.PROTECTED);
            return;
        }
        // Pre-versioned installations also persisted POSTING before issuing their approved CREATE request.
        if ("POSTING".equals(draft.syncPhase()) && store.db().sql("""
                SELECT COUNT(*) FROM mc_event WHERE entity_type = 'KNOWLEDGE' AND entity_id = :id AND action = 'SYNC_POSTING'
                """).param("id", draft.id()).query(Integer.class).single() > 0) return;
        if (plan == null || draft.syncPhase() != null) throw conflict("同步前须检查 4.23 重复与差异，并完成当前稿件的人工审核");
    }

    private String saveRecord(Knowledge knowledge, Material material, KnowledgeTemplates.Schema schema,
                              SyncReviews.Approved approval, String token, String actor) {
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
            workflow.validateBusinessValues(knowledge,true);
            business.verifyCreate(knowledge, material, approval.plan());
            var form = business.createForm(knowledge, material, schema);
            cdss.prepareWrite();
            checkpoint(knowledge.id(), token, actor, "POSTING", null, null);
            try {
                remoteId = cdss.create(form);
            } catch (CdssClient.Rejected e) {
                checkpoint(knowledge.id(), token, actor, null, null, null);
                throw e;
            }
        }
        business.verifyCreated(knowledge, material, business.snapshot(remoteId, category), false);
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
        if (!business.targetCategory(current, knowledge, schema.category())) categoryChanged(knowledge);
        if (knowledge.syncPhase() == null && !plan.baseline().equals(BusinessKnowledge.fingerprint(current))) {
            throw new BusinessKnowledge.ReviewChanged("4.23 原业务记录在审核后发生变化，请重新对比并审核");
        }
        if (plan.operation() == BusinessKnowledge.Operation.SKIP) {
            if (!business.receipt(knowledge,material,current,plan).verified()) {
                throw new BusinessKnowledge.ReviewChanged("4.23 正文或原件与审核时不同，请重新查重并审核");
            }
            complete(id, token, actor, remoteId, approval, current, true);
            return;
        }
        origins.requireOwned(remoteId);
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
                workflow.validateBusinessValues(knowledge,true);
                cdss.prepareWrite();
                checkpoint(id, token, actor, "UPDATE_POSTING", remoteId, null);
                cdss.update(remoteId, business.updateForm(plan,schema.category(),plan.form().get("content")));
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
                cdss.update(remoteId, business.updateForm(plan,schema.category(),references));
                current = business.snapshot(remoteId, schema.category());
                if (business.restoreReferences(plan, current) != null || !business.matchesUpdate(plan, current, id, true)) {
                    throw new CdssClient.RemoteFailure("4.23 更新回读时未保留已审核正文或参考文献");
                }
            }
        }
        if (!business.receipt(knowledge,material,current,plan).verified()) {
            throw new CdssClient.RemoteFailure("4.23 回读的标题、类别、摘要或 PDF 与已审核更新不一致");
        }
        complete(id, token, actor, remoteId, approval, current, false);
    }

    private void syncPdf(Knowledge knowledge, Material material, String remoteId, String token, String actor) {
        origins.requireOwned(remoteId);
        if ("ATTACHED".equals(knowledge.syncPhase())) return;
        String attachment = knowledge.attachmentUrl();
        if (attachment == null) {
            byte[] original = store.bytes(material);
            attachment = cdss.uploadPdf(knowledge.id(),original);
            checkpoint(knowledge.id(), token, actor, "UPLOADED", remoteId, attachment);
        }
        if (!material.sha256().equals(cdss.pdfChecksum(attachment))) {
            throw new CdssClient.RemoteFailure("4.23 上传的 PDF 校验值与原件不符，未确认附件绑定");
        }
        if (!cdss.hasAttachment(remoteId, attachment)) {
            cdss.bindAttachment(remoteId, attachment, store.displayFileName(material), knowledge.id().replace("-", ""));
        }
        if (!cdss.hasAttachment(remoteId, attachment)) {
            throw new CdssClient.RemoteFailure("4.23 已响应附件绑定，复查时尚未找到附件");
        }
        checkpoint(knowledge.id(), token, actor, "ATTACHED", remoteId, attachment);
    }

    BusinessKnowledge.Receipt receipt(String id) {
        Knowledge draft = store.knowledge(id);
        if (draft.remoteId() == null || draft.status() != KnowledgeStatus.SYNCED) throw conflict("该稿件尚无已完成的业务同步回执");
        var current = business.snapshot(draft.remoteId(),workflow.schema(draft).category());
        store.db().sql("UPDATE mc_knowledge SET business_release = :state WHERE remote_id = :remote AND status = 'SYNCED'")
                .param("state", current.path("releaseStatus").asText(null), Types.VARCHAR).param("remote",draft.remoteId()).update();
        return business.receipt(draft,store.material(draft.materialId()),current,reviews.approved(id).map(SyncReviews.Approved::plan).orElse(null));
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
            store.lockActiveMaterial(knowledge.materialId());
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
            if ("CREATED".equals(phase)) origins.recordCreation(remote, id);
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
                    UPDATE mc_knowledge SET status = 'SYNCED', remote_id = :remote, business_release = :release,
                      sync_phase = CASE WHEN :skip = 1 THEN 'SKIPPED' ELSE sync_phase END,
                      sync_lease = NULL, sync_token = NULL, sync_error = NULL, revision = revision + 1, updated_at = :now
                    WHERE id = :id AND status = 'SYNCING' AND sync_token = :token
                    """).param("id", id).param("remote", remoteId).param("release",actual.path("releaseStatus").asText(null), Types.VARCHAR)
                    .param("skip", skipped ? 1 : 0).param("now", store.now())
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
