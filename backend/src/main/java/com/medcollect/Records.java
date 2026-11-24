package com.medcollect;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

final class Records {
    private Records() {}

    enum Mode { URL, EUROPE_PMC, WEB, UPLOAD, BUSINESS }
    enum CaptureMode { SINGLE, LINKS }
    enum ScheduleMode { MANUAL, INTERVAL, ONCE, DAILY }
    enum AccessState { PUBLIC, PENDING, AUTHORIZED }
    enum MaterialStatus { PENDING, APPROVED, RETURNED }
    enum KnowledgeStatus { DRAFT, PENDING, APPROVED, RETURNED, SYNCING, SYNC_FAILED, SYNCED, REFERENCE_ONLY }
    enum RunStatus { RUNNING, SUCCEEDED, PARTIAL, FAILED }

    record Page<T>(List<T> rows, long total, int page, int size) {}
    record Source(String id, String name, String url, String note, boolean enabled, long createdAt, AccessState accessState) {}
    record SourceInput(@NotBlank @Size(max = 120) String name, @NotBlank @Size(max = 1000) String url,
                       @Size(max = 500) String note, boolean enabled, AccessState accessState) {}
    record Task(String id, String name, @JsonProperty("mode") Mode collectionMode, String queryText, String sourceId, CaptureMode captureMode,
                int resultLimit, int intervalMinutes, Long nextRunAt, boolean enabled, String createdBy,
                long createdAt, Long lastRunAt, String leaseToken, Long leaseUntil, int revision,
                ScheduleMode scheduleMode, Long scheduleAt, String dailyTime, boolean selectResults) {}
    record TaskInput(@NotBlank @Size(max = 120) String name, @NotNull Mode mode,
                     @Size(max = 1000) String queryText, String sourceId, @NotNull CaptureMode captureMode,
                     @Min(0) @Max(100) int resultLimit, @Min(0) @Max(525600) int intervalMinutes,
                     boolean enabled, Integer revision, ScheduleMode scheduleMode, Long scheduleAt,
                     @Size(max = 5) String dailyTime, boolean selectResults,
                     @Size(max = CollectionService.MAX_TARGETS) List<@NotBlank @Size(max = 36) String> sourceIds,
                     @Size(max = CollectionService.MAX_TARGETS) List<@NotBlank @Size(max = 1000) String> urls,
                     @Size(max=100) List<@NotBlank @Size(max=36) String> materialTypeIds) {}
    record Run(String id, String taskId, RunStatus status, String actor, long startedAt, Long finishedAt,
               int newCount, int duplicateCount, int failureCount, String note, String parentRunId, int metadataCount) {}
    record RunResult(String id, String runId, String materialId, String title, String url, String status, String note,
                     String sourceId) {}
    record CandidateSelection(@NotNull @Size(min = 1, max = 100) List<@NotBlank @Size(max = 36) String> resultIds) {}

    record RunOverview(String taskId, String id, RunStatus status, int newCount, int duplicateCount,
                       int failureCount, int metadataCount, int candidateCount) {}
    record TaskSummary(@JsonUnwrapped Task task, RunOverview latestRun) {}
    record MaterialList(String id, String title, String formatCode, String fileName, String sourceUrl,
                        String sourceName, long capturedAt, MaterialStatus status, String fetchedUrl, String citation) {}
    record Material(String id, String fingerprint, String sha256, String title, String formatCode, String fileName,
                    String mimeType, long sizeBytes, String sourceUrl, String fetchedUrl, String sourceName,
                    String authors, String publisher, String published, String externalRef, String sourceAbstract,
                    MaterialStatus status, long capturedAt, int revision) {
        boolean pdf() { return "PDF".equals(formatCode); }
    }
    record MaterialDetail(@JsonUnwrapped Material material, String rawText) {}
    record Acquired(String title, String formatCode, String fileName, String mimeType, String sourceUrl,
                    String fetchedUrl, String sourceName, String authors, String publisher, String published,
                    String externalRef, String sourceAbstract, String rawText, byte[] bytes) {}
    record Registration(String materialId, boolean duplicate) {}
    record Decision(@Positive int revision, boolean approved, @Size(max = 1000) String note,
                    @Size(max = 36) String reviewId) {}
    record MaterialDecision(@Positive int revision, boolean approved, @Size(max = 1000) String note,
                            @Size(max = 12000) String sourceAbstract) {}

    record KnowledgeList(String id, String materialId, String materialTitle, String title, String typeCode,
                         KnowledgeStatus status, long updatedAt, String remoteId, String syncPhase,
                         String reviewer, Long reviewedAt, Long aiReviewedAt, String deliveredOperation, String businessRelease) {}
    record Knowledge(String id, String materialId, String title, String typeCode, String knowledgeClass,
                     String summary, String templateJson, String fieldsJson, String relationsJson,
                     KnowledgeStatus status, int revision, String modelName, long createdAt, long updatedAt,
                     String remoteId, String attachmentUrl, String syncPhase, Long syncLease,
                     String syncToken, String syncError, String updateTargetId) {
        Knowledge withText(String body, String values) {
            return new Knowledge(id,materialId,title,typeCode,knowledgeClass,body,templateJson,values,relationsJson,status,
                    revision,modelName,createdAt,updatedAt,remoteId,attachmentUrl,syncPhase,syncLease,syncToken,syncError,updateTargetId);
        }
    }
    record DraftInput(@NotBlank @Size(max = 20) String typeCode, @Size(max = 60) String knowledgeClass,
                      boolean useAi, java.util.UUID requestId) {}
    record DraftEdit(@Positive int revision, @NotBlank @Size(max = 100) String title,
                     @Size(max = 12000) String summary, Map<String, String> fields,
                     Map<String, List<String>> relations, @Size(max=60) String knowledgeClass) {}
    record Event(String id, String entityType, String entityId, String action, String actor, long occurredAt, String note) {}
    record Dashboard(long tasks, long materials, long pendingMaterials, long pendingKnowledge, long synced, long metadata,
                     long unparsed, long readyToSync, long syncFailed, long written, long skipped) {}
}
