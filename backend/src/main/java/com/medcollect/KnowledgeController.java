package com.medcollect;

import static com.medcollect.Records.*;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@Validated
class KnowledgeController {
    private final KnowledgeWorkflow workflow;
    private final KnowledgeTemplates templates;
    private final Store store;
    private final SyncService sync;
    private final SyncReviews reviews;
    private final ModelService model;

    record Change(@Positive int revision, @Size(max = 1000) String note) {}
    record Decline(@Positive int revision, @Size(max = 36) String reviewId, @Size(max = 1000) String note) {}
    record ReviewInput(@Positive int revision, @Size(max = 80) String targetId, boolean manual) {}

    @GetMapping("/knowledge/{id}/business-records/{remoteId}")
    BusinessKnowledge.Reference businessRecord(@PathVariable String id, @PathVariable String remoteId) {
        return reviews.reference(id, remoteId);
    }

    @PostMapping("/knowledge/{id}/sync-decline")
    KnowledgeWorkflow.Detail decline(@PathVariable String id, @Valid @RequestBody Decline input, Principal actor) {
        return reviews.decline(id, input.revision(), input.reviewId(), input.note(), actor.getName());
    }

    KnowledgeController(KnowledgeWorkflow workflow, KnowledgeTemplates templates,
                        Store store, SyncService sync, SyncReviews reviews, ModelService model) {
        this.workflow = workflow;
        this.templates = templates;
        this.store = store;
        this.sync = sync;
        this.reviews = reviews;
        this.model = model;
    }

    @GetMapping("/categories")
    List<KnowledgeTemplates.Category> categories() { return templates.categories(); }

    @GetMapping("/categories/{code}/classes")
    List<KnowledgeTemplates.ClassOption> classes(@PathVariable String code) { return templates.classes(code); }

    @GetMapping("/categories/{code}/template")
    KnowledgeTemplates.Schema template(@PathVariable String code, @RequestParam(required = false) String knowledgeClass) {
        return templates.schema(code, knowledgeClass);
    }

    @GetMapping("/knowledge")
    Page<KnowledgeList> list(@RequestParam(defaultValue = "") @Size(max = 400) String query,
                             @RequestParam(required = false) KnowledgeStatus status,
                             @RequestParam(required = false) String materialId, @RequestParam(required = false) String human,
                             @RequestParam(required = false) String sync, @RequestParam(required = false) String subjectId,
                             @RequestParam(required = false) String submission, @RequestParam(required = false) String publication,
                             @RequestParam(required = false) @Size(max = 36) String classificationId,
                             @RequestParam(required = false) @Size(max = 20) String typeCode,
                             @RequestParam(required = false) @Size(max = 60) String knowledgeClass,
                             @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return workflow.list(query, status, materialId, human, sync, subjectId, submission, publication,
                classificationId, typeCode, knowledgeClass, page, size);
    }

    @GetMapping("/knowledge/{id}")
    Map<String, Object> detail(@PathVariable String id) {
        var knowledge = workflow.detail(id);
        var original = store.material(knowledge.materialId());
        var result = new LinkedHashMap<String, Object>();
        result.put("knowledge", knowledge);
        result.put("material", new MaterialList(original.id(), original.title(),
                        original.formatCode(), original.fileName(), original.sourceUrl(), original.sourceName(),
                        original.capturedAt(), original.status(), original.fetchedUrl(), KnowledgeText.citation(original),
                        original.archivedAt(), original.archivedBy(), original.archiveReason(), store.displayFileName(original)));
        result.put("events", store.events("KNOWLEDGE", id));
        var draft = store.knowledge(id);
        result.put("syncReview", reviews.preview(draft));
        result.put("canRevise", reviews.canRevise(draft));
        return result;
    }

    @PostMapping("/materials/{id}/drafts")
    KnowledgeWorkflow.Detail parse(@PathVariable String id, @Valid @RequestBody DraftInput input, Principal actor) {
        return workflow.create(id, input, actor.getName());
    }

    @PutMapping("/knowledge/{id}")
    KnowledgeWorkflow.Detail edit(@PathVariable String id, @Valid @RequestBody DraftEdit input, Principal actor) {
        return workflow.edit(id, input, actor.getName());
    }

    @PostMapping("/knowledge/{id}/submit")
    KnowledgeWorkflow.Detail submit(@PathVariable String id, @Valid @RequestBody Change input, Principal actor) {
        return workflow.submit(id, input.revision(), actor.getName());
    }

    @PostMapping("/knowledge/{id}/decision")
    KnowledgeWorkflow.Detail decision(@PathVariable String id, @Valid @RequestBody Decision input, Principal actor) {
        return reviews.decision(id, input, actor.getName());
    }

    @PostMapping("/knowledge/{id}/sync-review")
    SyncReviews.Preview syncReview(@PathVariable String id, @Valid @RequestBody ReviewInput input, Principal actor) {
        return reviews.prepare(id, input.revision(), input.targetId(), input.manual(), actor.getName());
    }

    @GetMapping("/knowledge/{id}/sync-targets")
    Page<BusinessKnowledge.Candidate> targets(@PathVariable String id, @RequestParam @Size(min=1,max=100) String query,
            @RequestParam(defaultValue="1") @Positive int page) { return reviews.targets(id,query,page); }

    @PostMapping("/knowledge/{id}/revisions")
    KnowledgeWorkflow.Detail revision(@PathVariable String id, @Valid @RequestBody Change input, Principal actor) {
        return reviews.revise(id, input.revision(), actor.getName());
    }

    @GetMapping("/knowledge/{id}/sync-versions")
    Page<SyncReviews.Version> versions(@PathVariable String id, @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return reviews.versions(id, page, size);
    }

    @GetMapping("/knowledge/{id}/sync-receipt")
    BusinessKnowledge.Receipt receipt(@PathVariable String id) { return sync.receipt(id); }

    @GetMapping("/knowledge/{id}/sync-versions/{versionId}")
    SyncReviews.VersionDetail version(@PathVariable String id, @PathVariable String versionId) {
        return reviews.version(id, versionId);
    }

    @PostMapping("/knowledge/{id}/pre-review")
    ModelService.ReviewAdvice preReview(@PathVariable String id, @Valid @RequestBody Change input, Principal actor) {
        return workflow.preReview(id, input.revision(), actor.getName());
    }

    @PostMapping("/knowledge/{id}/reopen")
    KnowledgeWorkflow.Detail reopen(@PathVariable String id, @Valid @RequestBody Change input, Principal actor) {
        return workflow.reopen(id, input.revision(), input.note(), actor.getName());
    }

    @PostMapping("/knowledge/{id}/sync")
    KnowledgeWorkflow.Detail sync(@PathVariable String id, @Valid @RequestBody Change input, Principal actor) {
        return sync.start(id, input.revision(), actor.getName());
    }

    @PostMapping("/knowledge/{id}/sync-reset")
    KnowledgeWorkflow.Detail reset(@PathVariable String id, @Valid @RequestBody Change input, Principal actor) {
        return sync.resetUncertain(id, input.revision(), input.note(), actor.getName());
    }

    @GetMapping("/settings")
    ModelService.View settings() { return model.settings(); }

    @PutMapping("/settings")
    ModelService.View settings(@Valid @RequestBody ModelService.Settings input, Principal actor) {
        return model.save(input, actor.getName());
    }

    @PostMapping("/settings/model-test")
    Map<String, String> testModel() { return model.test(); }

    @PostMapping("/settings/cdss-test")
    Map<String, Object> testCdss() {
        return Map.of("categories", templates.refresh(), "message", "4.23 鉴权、类别和模板接口已连通");
    }
}
