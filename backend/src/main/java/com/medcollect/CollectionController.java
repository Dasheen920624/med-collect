package com.medcollect;

import static com.medcollect.Records.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
@Validated
class CollectionController {
    private final Store store;
    private final CollectionService collection;
    private final MaterialService materials;
    private final ModelService model;
    private final CdssClient cdss;
    private final AppSettings settings;
    private final Classifications classifications;
    private final KnowledgeWorkflow knowledge;

    CollectionController(Store store, CollectionService collection, MaterialService materials,
                         ModelService model, CdssClient cdss, AppSettings settings,
                         Classifications classifications, KnowledgeWorkflow knowledge) {
        this.store = store;
        this.collection = collection;
        this.materials = materials;
        this.model = model;
        this.cdss = cdss;
        this.settings = settings;
        this.classifications = classifications;
        this.knowledge = knowledge;
    }

    @GetMapping("/csrf")
    Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }

    @GetMapping("/session")
    Map<String, Object> session(Authentication authentication) {
        return Map.of("username", authentication.getName(),
                "admin", authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")),
                "webSearch", !Support.trim(settings.searxUrl()).isBlank(), "cdssConfigured", cdss.configured(),
                "cdssSync", cdss.writeEnabled());
    }

    @GetMapping("/dashboard")
    Dashboard dashboard() { return store.dashboard(); }

    @GetMapping("/sources")
    List<Source> sources() { return collection.sources(); }

    @GetMapping("/sources/{id}")
    Map<String, Object> source(@PathVariable String id) {
        return Map.of("source", store.source(id), "tasks", collection.tasks(null, null, id, null, 1, 100),
                "events", store.events("SOURCE", id));
    }

    @PostMapping("/sources")
    Source addSource(@Valid @RequestBody SourceInput input, Principal actor) {
        return collection.saveSource(null, input, actor.getName());
    }

    @PutMapping("/sources/{id}")
    Source updateSource(@PathVariable String id, @Valid @RequestBody SourceInput input, Principal actor) {
        return collection.saveSource(id, input, actor.getName());
    }

    @GetMapping("/tasks")
    Page<Task> tasks(@RequestParam(defaultValue = "") @Size(max = 400) String query,
                     @RequestParam(required = false) Mode mode, @RequestParam(required = false) String sourceId,
                     @RequestParam(required = false) Boolean enabled, @RequestParam(defaultValue = "1") int page,
                     @RequestParam(defaultValue = "20") int size) {
        return collection.tasks(query, mode, sourceId, enabled, page, size);
    }

    @GetMapping("/tasks/{id}")
    Map<String, Object> task(@PathVariable String id) {
        return Map.of("task", store.task(id), "runs", collection.runs(id, 1, 30).rows(), "events", store.events("TASK", id),
                "sourceIds", collection.taskSourceIds(id), "urls", collection.taskUrls(id));
    }

    @PostMapping("/tasks")
    Task addTask(@Valid @RequestBody TaskInput input, Principal actor) {
        return collection.saveTask(null, input, actor.getName());
    }

    @PutMapping("/tasks/{id}")
    Task updateTask(@PathVariable String id, @Valid @RequestBody TaskInput input, Principal actor) {
        return collection.saveTask(id, input, actor.getName());
    }

    @PostMapping("/tasks/{id}/runs")
    Run start(@PathVariable String id, Principal actor) { return collection.start(id, actor.getName()); }

    @GetMapping("/runs")
    Page<Run> runs(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return collection.runs(null, page, size);
    }

    @GetMapping("/runs/{id}")
    Map<String, Object> run(@PathVariable String id) {
        Run run = store.run(id);
        return Map.of("run", run, "task", store.task(run.taskId()), "results", collection.results(id));
    }

    @PostMapping("/runs/{id}/capture")
    Run captureResults(@PathVariable String id, @Valid @RequestBody CandidateSelection input, Principal actor) {
        return collection.captureResults(id, input, actor.getName());
    }

    @PostMapping("/plans/suggest")
    List<ModelService.Suggestion> plan(@Valid @RequestBody ModelService.Plan input) {
        return model.plan(input.instruction());
    }

    @GetMapping("/materials")
    Page<MaterialList> materials(@RequestParam(defaultValue = "") @Size(max = 400) String query,
                                 @RequestParam(required = false) MaterialStatus status,
                                 @RequestParam(required = false) @Size(max = 12) String format,
                                 @RequestParam(required = false) @Size(max = 36) String classId,
                                 @RequestParam(defaultValue = "false") boolean originalsOnly,
                                 @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
        return materials.list(query, status, format, classId, originalsOnly, page, size);
    }

    @GetMapping("/materials/{id}")
    Map<String, Object> material(@PathVariable String id) {
        return Map.of("material", detail(store.material(id)), "runs", materials.runs(id),
                "events", store.events("MATERIAL", id), "classificationIds", classifications.materialClasses(id),
                "knowledge", knowledge.list(null, null, id, 1, 20));
    }

    @PostMapping(value = "/materials/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Registration upload(@RequestParam MultipartFile file, @RequestParam(required = false) @Size(max = 400) String title,
                        @RequestParam(required = false) @Size(max = 1000) String sourceUrl,
                        @RequestParam(required = false) String taskId, Principal actor) throws IOException {
        return collection.upload(file, title, sourceUrl, taskId, actor.getName());
    }

    @GetMapping("/materials/{id}/original")
    ResponseEntity<byte[]> original(@PathVariable String id, @RequestParam(defaultValue = "false") boolean preview) {
        Material material = store.material(id);
        boolean inline = preview && material.pdf();
        var disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(material.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(inline ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(store.bytes(material));
    }

    @PostMapping("/materials/{id}/decision")
    MaterialDetail materialDecision(@PathVariable String id, @Valid @RequestBody MaterialDecision input, Principal actor) {
        return materials.decide(id, input, actor.getName());
    }

    private MaterialDetail detail(Material material) { return new MaterialDetail(material, store.extractedText(material)); }
}
