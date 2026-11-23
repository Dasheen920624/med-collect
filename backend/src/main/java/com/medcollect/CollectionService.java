package com.medcollect;

import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.io.IOException;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
class CollectionService {
    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);
    private record Target(String url, Source source) {}
    private static class Counts {
        int added, duplicate, failed, metadata, candidates;
        int total() { return added + duplicate + failed + metadata + candidates; }
    }
    private final Store store;
    private final MaterialService materials;
    private final DocumentReader reader;
    private final Acquisition acquisition;
    private final AppSettings settings;
    private final TransactionTemplate transactions;
    private final Executor workers;

    CollectionService(Store store, MaterialService materials, DocumentReader reader, Acquisition acquisition,
                      AppSettings settings, TransactionTemplate transactions, @Qualifier("workExecutor") Executor workers) {
        this.store = store;
        this.materials = materials;
        this.reader = reader;
        this.acquisition = acquisition;
        this.settings = settings;
        this.transactions = transactions;
        this.workers = workers;
    }

    record SourceRow(String id, String name, String url, String note, boolean enabled, long createdAt,
                     AccessState accessState, long taskCount) {}

    List<SourceRow> sources() {
        return store.db().sql("""
                SELECT s.*, (SELECT COUNT(*) FROM mc_task t WHERE t.source_id = s.id OR EXISTS
                  (SELECT 1 FROM mc_task_source ts WHERE ts.task_id = t.id AND ts.source_id = s.id)) task_count
                FROM mc_source s ORDER BY s.created_at DESC, s.id
                """).query(SourceRow.class).list();
    }

    @Transactional
    Source saveSource(String id, SourceInput input, String actor) {
        boolean creating = id == null;
        String sourceId = creating ? id() : id;
        String url = httpUri(input.url()).toString();
        if (url.length() > 1000) throw bad("资料地址超过 1000 字符");
        AccessState access = input.accessState() == null
                ? creating ? AccessState.PUBLIC : store.source(sourceId).accessState()
                : input.accessState();
        var params = new MapSqlParameterSource().addValue("id", sourceId).addValue("name", input.name().strip())
                .addValue("url", url).addValue("note", trim(input.note()), Types.VARCHAR)
                .addValue("enabled", input.enabled() ? 1 : 0).addValue("now", store.now())
                .addValue("access", access.name());
        String statement = creating ? """
                INSERT INTO mc_source (id, name, url, note, enabled, created_at, access_state)
                VALUES (:id, :name, :url, :note, :enabled, :now, :access)
                """ : """
                UPDATE mc_source SET name = :name, url = :url, note = :note, enabled = :enabled, access_state = :access
                WHERE id = :id
                """;
        changed(store.db().sql(statement).paramSource(params).update());
        store.event("SOURCE", sourceId, creating ? "CREATED" : "UPDATED", actor, url);
        return store.source(sourceId);
    }

    @Transactional
    void enableSource(String id, boolean enabled, String actor) {
        store.source(id);
        int changed = store.db().sql("UPDATE mc_source SET enabled = :enabled WHERE id = :id AND enabled <> :enabled")
                .param("enabled", enabled ? 1 : 0).param("id", id).update();
        if (changed > 0) store.event("SOURCE", id, "UPDATED", actor, enabled ? "启用来源" : "停用来源");
    }

    Page<TaskSummary> tasks(String query, Mode mode, String sourceId, Boolean enabled, String state, Long fromTime,
                            Long untilTime, String subjectId, int page, int size) {
        var params = new HashMap<String, Object>();
        String from = "FROM mc_task t WHERE 1=1";
        if (!trim(query).isBlank()) {
            from += " AND (INSTR(LOWER(t.name), :q) > 0 OR INSTR(LOWER(t.query_text), :q) > 0)";
            params.put("q", query.strip().toLowerCase(Locale.ROOT));
        }
        if (mode != null) {
            from += " AND t.collection_mode = :mode";
            params.put("mode", mode.name());
        }
        if (!trim(sourceId).isBlank()) {
            from += " AND (t.source_id = :source OR EXISTS (SELECT 1 FROM mc_task_source s"
                    + " WHERE s.task_id = t.id AND s.source_id = :source))";
            params.put("source", sourceId);
        }
        if (enabled != null) {
            from += " AND t.enabled = :enabled";
            params.put("enabled", enabled ? 1 : 0);
        }
        from += Classifications.taskScope(subjectId, params);
        if (!trim(state).isBlank()) {
            from += " AND COALESCE((SELECT r.status FROM mc_run r WHERE r.task_id = t.id"
                    + " ORDER BY r.started_at DESC, r.id DESC FETCH FIRST 1 ROW ONLY), 'WAITING') = :state";
            params.put("state", state);
        }
        if (fromTime != null) { from += " AND t.created_at >= :fromTime"; params.put("fromTime", fromTime); }
        if (untilTime != null) { from += " AND t.created_at < :untilTime"; params.put("untilTime", untilTime); }
        Page<Task> result = store.page("t.*", from, "t.created_at DESC, t.id", params, page, size, Task.class);
        var recent = new HashMap<String, RunOverview>();
        if (!result.rows().isEmpty()) {
            store.db().sql("""
                    SELECT r.task_id, r.id, r.status, r.new_count, r.duplicate_count, r.failure_count, r.metadata_count,
                      (SELECT COUNT(*) FROM mc_run_result p WHERE p.run_id = r.id AND p.status = 'CANDIDATE') candidate_count
                    FROM (SELECT x.*, ROW_NUMBER() OVER(PARTITION BY task_id ORDER BY started_at DESC, id DESC) rn
                      FROM mc_run x WHERE x.task_id IN (:ids)) r WHERE r.rn = 1
                    """).param("ids", result.rows().stream().map(Task::id).toList()).query(RunOverview.class).list()
                    .forEach(run -> recent.put(run.taskId(), run));
        }
        return new Page<>(result.rows().stream().map(t -> new TaskSummary(t, recent.get(t.id()))).toList(),
                result.total(), result.page(), result.size());
    }

    List<String> taskSourceIds(String id) {
        return store.db().sql("SELECT source_id FROM mc_task_source WHERE task_id = :id ORDER BY item_order")
                .param("id", id).query(String.class).list();
    }

    List<String> taskUrls(String id) {
        return store.db().sql("SELECT capture_url FROM mc_task_url WHERE task_id = :id ORDER BY item_order")
                .param("id", id).query(String.class).list();
    }

    @Transactional
    Task saveTask(String id, TaskInput input, String actor) {
        boolean creating = id == null;
        String taskId = creating ? id() : id;
        Task previous = creating ? null : store.task(id);
        boolean namedTargets = input.sourceIds() != null || input.urls() != null;
        List<String> sourceIds = input.sourceIds() == null ? List.of() : input.sourceIds().stream().distinct().toList();
        sourceIds.forEach(store::source);
        List<String> urls = input.urls() == null ? List.of() : input.urls().stream().map(u -> httpUri(u).toString())
                .distinct().toList();
        String sourceId = namedTargets ? sourceIds.stream().findFirst().orElse(null) :
                trim(input.sourceId()).isBlank() ? null : store.source(input.sourceId()).id();
        if (sourceIds.size() + urls.size() > 20) throw bad("单个任务最多配置 20 个采集目标");
        if (urls.stream().anyMatch(u -> u.length() > 1000)) throw bad("资料地址超过 1000 字符");
        if (input.mode() != Mode.URL && !urls.isEmpty()) throw bad("仅指定网址任务支持额外采集地址");
        String query = trim(input.queryText());
        if (input.mode() == Mode.URL) {
            if (namedTargets) query = urls.stream().findFirst().orElse("");
            if (query.isBlank() && sourceId == null) throw bad("请输入采集网址或选择资料来源");
            if (!query.isBlank()) query = httpUri(query).toString();
            if (query.length() > 1000) throw bad("资料地址超过 1000 字符");
        } else if (input.mode() == Mode.UPLOAD) {
            if (sourceIds.size() > 1) throw bad("手工上传请选择一个资料来源");
        } else {
            requireText(query, "请输入检索关键词");
            if (input.mode() == Mode.WEB && trim(settings.searxUrl()).isBlank()) {
                throw bad("服务端尚未配置全网检索");
            }
            if (input.mode() == Mode.EUROPE_PMC && sourceId != null) throw bad("文献检索的资料来源为 Europe PMC");
        }
        if (input.selectResults() && input.mode() != Mode.WEB) throw bad("仅全网搜索支持选择结果后采集");
        long now = store.now();
        var schedule = CaptureSchedule.of(input);
        Long next = schedule.afterSave(previous, input.enabled(), now);
        var params = new MapSqlParameterSource().addValue("id", taskId).addValue("name", input.name().strip())
                .addValue("mode", input.mode().name()).addValue("query", query, Types.VARCHAR)
                .addValue("source", sourceId, Types.VARCHAR)
                .addValue("capture", input.captureMode().name()).addValue("limit", input.resultLimit())
                .addValue("interval", schedule.minutes()).addValue("enabled", input.enabled() ? 1 : 0)
                .addValue("now", now).addValue("actor", actor).addValue("next", next, Types.NUMERIC)
                .addValue("schedule", schedule.mode().name()).addValue("at", schedule.at(), Types.NUMERIC)
                .addValue("daily", schedule.dailyTime(), Types.VARCHAR).addValue("select", input.selectResults() ? 1 : 0)
                .addValue("revision", input.revision(), Types.NUMERIC);
        String statement = creating ? """
                INSERT INTO mc_task (id, name, collection_mode, query_text, source_id, capture_mode, result_limit,
                  interval_minutes, next_run_at, enabled, created_by, created_at, revision,
                  schedule_mode, schedule_at, daily_time, select_results)
                VALUES (:id, :name, :mode, :query, :source, :capture, :limit, :interval, :next, :enabled, :actor, :now, 1,
                  :schedule, :at, :daily, :select)
                """ : """
                UPDATE mc_task SET name = :name, collection_mode = :mode, query_text = :query, source_id = :source, capture_mode = :capture,
                  result_limit = :limit, interval_minutes = :interval, next_run_at = :next, enabled = :enabled,
                  schedule_mode = :schedule, schedule_at = :at, daily_time = :daily, select_results = :select, revision = revision + 1
                WHERE id = :id AND revision = :revision AND (lease_until IS NULL OR lease_until < :now)
                """;
        changed(store.db().sql(statement).paramSource(params).update());
        saveTargets(taskId, sourceIds, urls);
        store.event("TASK", taskId, creating ? "CREATED" : "UPDATED", actor, query);
        return store.task(taskId);
    }

    private void saveTargets(String task, List<String> sources, List<String> urls) {
        store.db().sql("DELETE FROM mc_task_source WHERE task_id = :id").param("id", task).update();
        store.db().sql("DELETE FROM mc_task_url WHERE task_id = :id").param("id", task).update();
        for (int i = 0; i < sources.size(); i++) {
            store.db().sql("INSERT INTO mc_task_source (task_id, source_id, item_order) VALUES (:id, :source, :pos)")
                    .params(Map.of("id", task, "source", sources.get(i), "pos", i)).update();
        }
        for (int i = 0; i < urls.size(); i++) {
            store.db().sql("INSERT INTO mc_task_url (task_id, item_order, capture_url) VALUES (:id, :pos, :url)")
                    .params(Map.of("id", task, "pos", i, "url", urls.get(i))).update();
        }
    }

    Page<Run> runs(String taskId, int page, int size) {
        return taskId == null ? store.page("*", "FROM mc_run", "started_at DESC, id", Map.of(), page, size, Run.class)
                : store.page("*", "FROM mc_run WHERE task_id = :task", "started_at DESC, id",
                        Map.of("task", taskId), page, size, Run.class);
    }

    List<RunResult> results(String runId) {
        store.run(runId);
        return store.db().sql("SELECT * FROM mc_run_result WHERE run_id = :id ORDER BY title, id")
                .param("id", runId).query(RunResult.class).list();
    }

    Run start(String taskId, String actor) {
        Run run = enqueue(taskId, actor, false, null, List.of());
        if (run == null) throw conflict("该任务正在执行");
        return run;
    }

    Run captureResults(String searchId, CandidateSelection input, String actor) {
        Run search = store.run(searchId);
        if (search.status() == RunStatus.RUNNING) throw conflict("请等待搜索结束");
        var ids = new HashSet<>(input.resultIds());
        var selected = results(searchId).stream()
                .filter(row -> "CANDIDATE".equals(row.status()) && ids.contains(row.id())).toList();
        if (selected.size() != ids.size()) throw bad("请选择本次执行中待采集的搜索结果");
        Run run = enqueue(search.taskId(), actor, false, searchId, selected);
        if (run == null) throw conflict("该任务正在执行");
        return run;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 5000)
    void schedule() {
        if (!settings.schedulingEnabled()) return;
        long now = store.now();
        store.db().sql("""
                UPDATE mc_run r SET status = 'FAILED', finished_at = :now,
                  failure_count = 1, note = '执行租约已过期，任务可重新执行'
                WHERE r.status = 'RUNNING' AND NOT EXISTS (
                    SELECT 1 FROM mc_task t WHERE t.id = r.task_id AND t.lease_token = r.id AND t.lease_until >= :now)
                """).param("now", now).update();
        List<String> due = store.db().sql("""
                SELECT id FROM mc_task WHERE enabled = 1 AND collection_mode <> 'UPLOAD' AND next_run_at <= :now
                AND (lease_until IS NULL OR lease_until < :now)
                ORDER BY next_run_at FETCH FIRST 6 ROWS ONLY
                """).param("now", now).query(String.class).list();
        for (String id : due) {
            try {
                enqueue(id, "scheduler", true, null, List.of());
            } catch (RuntimeException e) {
                log.warn("Scheduled acquisition could not be queued for task {}", id, e);
            }
        }
    }

    private Run enqueue(String taskId, String actor, boolean scheduled, String parentRun, List<RunResult> selected) {
        Run run = claim(taskId, actor, scheduled, false, parentRun);
        if (run == null) return null;
        try {
            workers.execute(() -> execute(run, selected));
        } catch (org.springframework.core.task.TaskRejectedException e) {
            finish(run, 0, 0, 1, "当前执行队列已满", RunStatus.FAILED);
            return store.run(run.id());
        }
        return run;
    }

    private Run claim(String taskId, String actor, boolean scheduled, boolean uploading, String parentRun) {
        return transactions.execute(tx -> {
            Task task = store.task(taskId);
            if ((task.collectionMode() == Mode.UPLOAD) != uploading) {
                throw bad(uploading ? "请选择手工上传类型的任务" : "手工上传任务请选择原件文件");
            }
            String runId = id();
            long now = store.now();
            Long next = scheduled ? CaptureSchedule.afterScheduledRun(task, now) : task.nextRunAt();
            String condition = scheduled ? " AND enabled = 1 AND next_run_at <= :now" : "";
            int updated = store.db().sql("""
                    UPDATE mc_task SET lease_token = :token, lease_until = :lease, next_run_at = :next, last_run_at = :now
                    WHERE id = :id AND revision = :revision AND (lease_until IS NULL OR lease_until < :now)
                    """ + condition).param("id", taskId).param("token", runId)
                    .param("lease", now + Duration.ofMinutes(90).toMillis())
                    .param("now", now).param("next", next, Types.NUMERIC).param("revision", task.revision()).update();
            if (updated == 0) return null;
            store.db().sql("""
                    UPDATE mc_run SET status = 'FAILED', finished_at = :now, note = '执行中断，任务可重新执行'
                    WHERE task_id = :id AND status = 'RUNNING'
                    """).param("id", taskId).param("now", now).update();
            store.db().sql("""
                    INSERT INTO mc_run (id, task_id, status, started_at, actor, parent_run_id)
                    VALUES (:id, :task, 'RUNNING', :now, :actor, :parent)
                    """).params(Map.of("id", runId, "task", taskId, "now", now, "actor", actor))
                    .param("parent", parentRun, Types.VARCHAR).update();
            return store.run(runId);
        });
    }

    private void execute(Run run, List<RunResult> selected) {
        var counts = new Counts();
        String note = null;
        try {
            if (selected.isEmpty()) discover(run, counts);
            else for (RunResult row : selected) {
                var candidate = new Acquisition.Candidate(row.title(), row.url(), null);
                Source source = row.sourceId() == null ? null : store.source(row.sourceId());
                acquire(run, candidate, source, counts);
            }
            if (counts.total() == 0) note = "没有匹配的检索结果";
            if (counts.candidates > 0) note = "搜索完成，选择结果后采集";
        } catch (Exception e) {
            counts.failed++;
            note = message(e);
            if (!(e instanceof IOException || e instanceof ResponseStatusException)) {
                log.warn("Acquisition run {} failed", run.id(), e);
            }
        }
        RunStatus status = counts.failed == 0 ? RunStatus.SUCCEEDED
                : counts.total() == counts.failed ? RunStatus.FAILED : RunStatus.PARTIAL;
        finish(run, counts.added, counts.duplicate, counts.failed, counts.metadata, note, status);
    }

    private void discover(Run run, Counts counts) {
        Task task = store.task(run.taskId());
        var seen = new HashSet<String>();
        for (Target target : targets(task)) {
            if (counts.total() >= task.resultLimit()) break;
            try {
                available(target.source());
                for (var candidate : acquisition.discover(task, target.source(), target.url())) {
                    if (counts.total() >= task.resultLimit()) break;
                    if (!seen.add(candidate.url())) continue;
                    if (task.selectResults()) {
                        materials.result(run.id(), null, candidate.title(), candidate.url(), "CANDIDATE", null,
                                target.source() == null ? null : target.source().id());
                        counts.candidates++;
                    } else {
                        acquire(run, candidate, target.source(), counts);
                    }
                }
            } catch (IOException | ResponseStatusException e) {
                counts.failed++;
                materials.result(run.id(), null, target.source() == null ? null : target.source().name(),
                        target.url(), "FAILED", message(e));
            }
        }
    }

    private void acquire(Run run, Acquisition.Candidate candidate, Source source, Counts counts) {
        try {
            available(source);
            var captured = acquisition.acquire(candidate, source);
            var registered = materials.register(captured.material(), run.actor(), run.id(), captured.note());
            if (registered.duplicate()) counts.duplicate++;
            else if ("METADATA".equals(captured.material().formatCode())) counts.metadata++;
            else counts.added++;
        } catch (IOException | ResponseStatusException e) {
            counts.failed++;
            materials.result(run.id(), null, candidate.title(), candidate.url(), "FAILED", message(e));
        }
    }

    private List<Target> targets(Task task) {
        var sources = taskSourceIds(task.id()).stream().map(store::source).toList();
        var urls = taskUrls(task.id());
        var targets = new ArrayList<Target>();
        sources.forEach(source -> targets.add(new Target(source.url(), source)));
        urls.forEach(url -> targets.add(new Target(url, null)));
        if (targets.isEmpty()) {
            Source source = task.sourceId() == null ? null : store.source(task.sourceId());
            String url = source == null ? null : source.url();
            targets.add(new Target(task.collectionMode() == Mode.URL ? DocumentReader.withDefault(task.queryText(), url) : url, source));
        }
        return targets;
    }

    Registration upload(MultipartFile file, String title, String sourceUrl, String taskId, String actor) throws IOException {
        String url = trim(sourceUrl).isBlank() ? null : httpUri(sourceUrl).toString();
        if (url != null && url.length() > 1000) throw bad("资料地址超过 1000 字符");
        String id = trim(taskId).isBlank() ? null : taskId;
        Run run = id == null ? null : claim(id, actor, false, true, null);
        if (id != null && run == null) throw conflict("该任务正在处理一份原件");
        try {
            Source source = run == null ? null : sourceFor(store.task(run.taskId()));
            if (url == null && source != null) url = source.url();
            Acquired material = reader.read(file.getBytes(), file.getOriginalFilename(), file.getContentType(),
                    url, null, source == null ? "手工上传" : source.name(), title);
            Registration registration = materials.register(material, actor, run == null ? null : run.id(), null);
            if (run != null) finish(run, registration.duplicate() ? 0 : 1, registration.duplicate() ? 1 : 0,
                    0, null, RunStatus.SUCCEEDED);
            return registration;
        } catch (IOException | RuntimeException e) {
            if (run != null) finish(run, 0, 0, 1, message(e), RunStatus.FAILED);
            throw e;
        }
    }

    private Source sourceFor(Task task) {
        if (task.sourceId() == null) return null;
        Source source = store.source(task.sourceId());
        available(source);
        return source;
    }

    private void available(Source source) {
        if (source == null) return;
        if (!source.enabled()) throw conflict("任务关联的资料来源已停用");
        if (source.accessState() == AccessState.PENDING) throw conflict("该资料来源尚未授权");
    }

    private void finish(Run run, int added, int duplicate, int failed, String note, RunStatus status) {
        finish(run, added, duplicate, failed, 0, note, status);
    }

    private void finish(Run run, int added, int duplicate, int failed, int metadata, String note, RunStatus status) {
        transactions.executeWithoutResult(tx -> {
            long now = store.now();
            store.db().sql("""
                    UPDATE mc_run SET status = :status, finished_at = :now, new_count = :added,
                      duplicate_count = :duplicate, failure_count = :failed, metadata_count = :metadata, note = :note
                    WHERE id = :id AND status = 'RUNNING'
                    """).param("status", status.name()).param("now", now).param("added", added).param("duplicate", duplicate)
                    .param("failed", failed).param("metadata", metadata)
                    .param("note", limit(note, 1000), Types.VARCHAR).param("id", run.id()).update();
            store.db().sql("UPDATE mc_task SET lease_token = NULL, lease_until = NULL WHERE id = :id AND lease_token = :token")
                    .param("id", run.taskId()).param("token", run.id()).update();
            store.event("TASK", run.taskId(), status.name(), run.actor(), run.id());
        });
    }

    private String message(Exception exception) {
        if (exception instanceof ResponseStatusException e) return e.getReason();
        if (exception instanceof IOException e && e.getMessage() != null) return limit(e.getMessage(), 400);
        return "采集执行失败，请查看服务端日志";
    }
}
