package com.medcollect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:workflow;MODE=Oracle;DB_CLOSE_DELAY=-1",
        "medcollect.scheduling-enabled=true",
        "medcollect.cdss-user=workflow-fixture",
        "medcollect.cdss-password=test-password",
        "medcollect.cdss-sync-enabled=true",
        "medcollect.private-source-hosts=127.0.0.1"
})
@AutoConfigureMockMvc
class WorkflowIntegrationTest {
    @TempDir static Path originals;
    private static final ObjectMapper WIRE = new ObjectMapper();
    private static final AtomicInteger CREATES = new AtomicInteger();
    private static final Map<String, AtomicInteger> FILE_WRITES = new ConcurrentHashMap<>();
    private static final AtomicInteger BINDS = new AtomicInteger();
    private static final AtomicReference<Map<String, String>> SAVED_FORM = new AtomicReference<>();
    private static final Map<String,byte[]> REMOTE_FILES = new ConcurrentHashMap<>();
    private static final AtomicReference<String> MODEL_REQUEST = new AtomicReference<>();
    private static final AtomicInteger EMPTY_ANALYSIS = new AtomicInteger();
    private static final AtomicInteger SOURCE_ATTEMPTS = new AtomicInteger();
    private static final Map<String, String> AFFIXES = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, String>> BUSINESS_RECORDS = new ConcurrentHashMap<>();
    private static final AtomicInteger UPDATES = new AtomicInteger();
    private static final AtomicReference<String> INTERRUPT_UPDATE = new AtomicReference<>();
    private static final Map<String, List<String>> BUSINESS_AFFIXES = new ConcurrentHashMap<>();
    private static final String OLD_VERSION_FILE = "knowledgeAffix/MedCollect/synthetic.pdf";
    private static volatile boolean referenceFailure;
    private static final String LIBRARY_ARTICLE = "fixture-literature-paper";
    private static final String TARGET_ID = "target-knowledge-id";
    private static final HttpServer REMOTE = remote();

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired Store store;
    @Autowired CollectionService collection;
    @Autowired SourceHttp sourceHttp;

    @Test
    void removesLocalMaterialAndDraftAndPreventsRecapture() throws Exception {
        byte[] bytes="<html><title>Removal fixture</title><body>Document for removal</body></html>".getBytes(StandardCharsets.UTF_8);
        var upload=multipart("/api/materials/upload").file(new MockMultipartFile("file","removed.html","text/html",bytes))
                .param("sourceUrl","https://example.org/removal-fixture").with(user("collector").roles("ADMIN")).with(csrf());
        String id=body(mvc.perform(upload)).path("materialId").asText();
        var m=postBody("/api/materials/"+id+"/decision",Map.of("revision",1,"approved",true,"sourceAbstract","Original"));
        var k=postBody("/api/materials/"+id+"/drafts",Map.of("typeCode","31","knowledgeClass","17","useAi",false));
        var path=originals.resolve(id.substring(0,2)).resolve(id);
        assertThat(path.resolve("original.html")).isRegularFile();
        var remove=delete("/api/materials/"+id).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("revision",m.path("revision").asInt(),"reason","移除无用测试资料"))).with(csrf());
        mvc.perform(remove.with(user("reviewer").roles("REVIEWER"))).andExpect(status().isForbidden());
        mvc.perform(remove.with(user("collector").roles("ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(remove.with(user("collector").roles("ADMIN"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/materials/"+id+"/original").with(user("reviewer"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/knowledge/"+k.path("id").asText()).with(user("reviewer"))).andExpect(status().isNotFound());
        assertThat(path).doesNotExist();
        mvc.perform(upload).andExpect(status().isConflict());
    }

    @DynamicPropertySource
    static void endpoints(DynamicPropertyRegistry environment) {
        environment.add("medcollect.cdss-url", WorkflowIntegrationTest::base);
        environment.add("medcollect.model-base-url", () -> base() + "/v1");
        environment.add("medcollect.searx-url", WorkflowIntegrationTest::base);
        environment.add("medcollect.original-dir", () -> originals.toString());
    }

    @AfterAll
    static void stop() { REMOTE.stop(0); }

    @Test
    void keepsPdfOriginalAndReconcilesInterruptedRemoteWrites() throws Exception {
        byte[] pdf = pdf();
        JsonNode uploaded = body(mvc.perform(multipart("/api/materials/upload")
                .file(new MockMultipartFile("file", "synthetic.pdf", "application/pdf", pdf))
                .param("sourceUrl", "https://example.org/literature/synthetic")
                .with(user("collector").roles("ADMIN")).with(csrf())));
        String material = uploaded.path("materialId").asText();
        JsonNode before = getBody("/api/materials/" + material).path("material");
        assertThat(before.path("formatCode").asText()).isEqualTo("PDF");
        assertThat(before.path("rawText").isNull()).isTrue();
        Path original = storedPath(material);
        assertThat(original.getFileName().toString()).isEqualTo("original.pdf");
        assertThat(Files.readAllBytes(original)).isEqualTo(pdf);
        assertThat(Files.exists(original.resolveSibling("extracted.txt"))).isFalse();
        var download = mvc.perform(get("/api/materials/{id}/original", material).with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(download).isEqualTo(pdf);
        var preview = mvc.perform(get("/api/materials/{id}/original", material).param("preview", "true")
                .with(user("reviewer").roles("REVIEWER"))).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(preview.getHeader("X-Frame-Options")).isEqualTo("SAMEORIGIN");
        assertThat(preview.getContentType()).isEqualTo("application/pdf");
        assertThat(preview.getContentAsByteArray()).isEqualTo(pdf);
        String pages = "/api/materials/" + material + "/pdf-preview";
        assertThat(getBody(pages).path("pages").asInt()).isOne();
        var page = mvc.perform(get(pages + "/1").with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(page.getContentType()).isEqualTo("image/jpeg");
        var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(page.getContentAsByteArray()));
        assertThat(image.getHeight()).isBetween(300,1680);
        mvc.perform(get(pages + "/2").with(user("reviewer").roles("REVIEWER"))).andExpect(status().isBadRequest());
        mvc.perform(get(pages + "/1")).andExpect(status().isUnauthorized());

        mvc.perform(post("/api/materials/{id}/drafts", material).contentType(MediaType.APPLICATION_JSON)
                .content("{\"typeCode\":\"31\",\"knowledgeClass\":\"17\",\"useAi\":true}")
                .with(user("reviewer").roles("REVIEWER")).with(csrf())).andExpect(status().isConflict());
        postBody("/api/materials/" + material + "/decision", Map.of("revision", 1, "approved", true));
        EMPTY_ANALYSIS.set(1);
        JsonNode draft = postBody("/api/materials/" + material + "/drafts",
                Map.of("typeCode", "31", "knowledgeClass", "17", "useAi", true));
        String knowledge = draft.path("id").asText();
        assertThat(MODEL_REQUEST.get()).contains("PDF raw test sentence", "上一次未返回有效的摘要对象");
        assertThat(draft.path("summary").asText()).isEqualTo("Synthetic reviewed abstract");
        String endpoint = "/api/knowledge/" + knowledge;
        mvc.perform(post(endpoint + "/sync").contentType(MediaType.APPLICATION_JSON)
                .content("{\"revision\":1}").with(user("reviewer").roles("REVIEWER")).with(csrf()))
                .andExpect(status().isConflict());

        JsonNode pending = postBody(endpoint + "/submit", Map.of("revision", draft.path("revision").asInt()));
        JsonNode advice = postBody(endpoint + "/pre-review", Map.of("revision", pending.path("revision").asInt()));
        assertThat(advice.path("findings").path(0).asText()).contains("核对");
        assertThat(getBody(endpoint).path("knowledge").path("status").asText()).isEqualTo("PENDING");
        JsonNode approved = compareAndApprove(endpoint, pending.path("revision").asInt(), "CREATE");
        postBody(endpoint + "/sync", Map.of("revision", approved.path("revision").asInt()));
        JsonNode uncertain = await(endpoint, "knowledge", "SYNC_FAILED");
        assertThat(uncertain.path("syncPhase").asText()).isEqualTo("POSTING");
        postBody(endpoint + "/sync", Map.of("revision", uncertain.path("revision").asInt()));
        JsonNode incomplete = await(endpoint, "knowledge", "SYNC_FAILED");
        assertThat(incomplete.path("remoteId").asText()).isEqualTo(TARGET_ID);
        assertThat(incomplete.path("syncPhase").asText()).isEqualTo("UPLOADED");
        String uploadPath = store.knowledge(knowledge).attachmentUrl();
        REMOTE_FILES.put(uploadPath,"%PDF-wrong native file".getBytes(StandardCharsets.US_ASCII));
        postBody(endpoint + "/sync", Map.of("revision", incomplete.path("revision").asInt()));
        var corrupt = await(endpoint,"knowledge","SYNC_FAILED");
        assertThat(corrupt.path("syncError").asText()).contains("校验值");
        assertThat(getBody(endpoint+"/sync-versions").path("total").asInt()).isZero();
        REMOTE_FILES.put(uploadPath,pdf);
        postBody(endpoint + "/sync", Map.of("revision", corrupt.path("revision").asInt()));
        JsonNode synced = await(endpoint, "knowledge", "SYNCED");
        assertThat(synced.path("remoteId").asText()).isEqualTo(TARGET_ID);
        assertThat(CREATES.get()).isEqualTo(1);
        assertThat(FILE_WRITES.get(uploadPath).get()).isOne();
        assertThat(uploadPath).endsWith(Support.sha(pdf) + ".pdf");
        assertThat(BINDS.get()).isEqualTo(1);
        assertThat(REMOTE_FILES.get(uploadPath)).isEqualTo(pdf);
        assertThat(getBody(endpoint + "/sync-receipt").path("pdfMatches").asBoolean()).isTrue();
        assertThat(SAVED_FORM.get()).containsEntry("type", "31").containsEntry("knowledgeClass", "17");
        JsonNode syncedContent = json.readTree(SAVED_FORM.get().get("content"));
        assertThat(syncedContent.path("content").path("abstract").asText()).contains("Synthetic reviewed abstract");
        assertThat(syncedContent.toString()).contains("https://example.org/literature/synthetic")
                .doesNotContain("PDF raw test sentence", "unapprovedMainBody");
    }

    @Test
    void comparesByDoiAndRequiresReviewOfChangedBusinessVersion() throws Exception {
        String doi = "10.1234/collection.version-test";
        String source = "https://doi.org/" + doi;
        String target = "existing-journal-target";
        String duplicate = "existing-journal-duplicate";
        var original = businessRecord(target, "Original business title", source);
        BUSINESS_RECORDS.put(target, original);
        BUSINESS_RECORDS.put(duplicate, businessRecord(duplicate, "Another matching business title", source));

        JsonNode material = body(mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file",
                        "version.html", "text/html", "<html><title>Updated journal title</title><body>Article text</body></html>"
                                .getBytes(StandardCharsets.UTF_8)))
                .param("sourceUrl", source).with(user("collector").roles("ADMIN")).with(csrf())));
        String materialId = material.path("materialId").asText();
        postBody("/api/materials/" + materialId + "/decision", Map.of("revision", 1, "approved", true,
                "sourceAbstract", "Updated reviewed abstract"));
        JsonNode draft = postBody("/api/materials/" + materialId + "/drafts", Map.of("typeCode", "31", "knowledgeClass", "17", "useAi", false));
        String endpoint = "/api/knowledge/" + draft.path("id").asText();
        JsonNode pending = postBody(endpoint + "/submit", Map.of("revision", draft.path("revision").asInt()));
        int revision = pending.path("revision").asInt();
        mvc.perform(post(endpoint + "/decision").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("revision", revision, "approved", true)))
                .with(user("reviewer").roles("REVIEWER")).with(csrf())).andExpect(status().isConflict());

        JsonNode choices = postBody(endpoint + "/sync-review", Map.of("revision", revision));
        assertThat(choices.path("operation").asText()).isEqualTo("CHOOSE");
        assertThat(choices.path("candidates").size()).isEqualTo(2);
        JsonNode compared = postBody(endpoint + "/sync-review", Map.of("revision", currentRevision(endpoint), "targetId", target));
        assertThat(compared.path("operation").asText()).isEqualTo("UPDATE");
        assertThat(compared.path("mayResetBusinessReview").asBoolean()).isTrue();
        assertThat(compared.path("target").path("basis").asText()).isEqualTo("DOI 一致");
        assertThat(compared.path("differences").toString()).contains("Old reviewed abstract", "Updated reviewed abstract");
        mvc.perform(post(endpoint + "/decision").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("revision", currentRevision(endpoint), "approved", true,
                        "reviewId", choices.path("id").asText())))
                .with(user("reviewer").roles("REVIEWER")).with(csrf())).andExpect(status().isConflict());
        approve(endpoint, compared);
        int updates = UPDATES.get();
        original.put("modifyTime", "4.23 changed after approval");
        postBody(endpoint + "/sync", Map.of("revision", currentRevision(endpoint)));
        JsonNode stale = await(endpoint, "knowledge", "PENDING");
        assertThat(stale.path("syncError").asText()).contains("重新对比");
        assertThat(UPDATES.get()).isEqualTo(updates);

        JsonNode refreshed = postBody(endpoint + "/sync-review", Map.of("revision", currentRevision(endpoint), "targetId", target));
        JsonNode approved = approve(endpoint, refreshed);
        INTERRUPT_UPDATE.set(target);
        postBody(endpoint + "/sync", Map.of("revision", approved.path("revision").asInt()));
        JsonNode interrupted = await(endpoint, "knowledge", "SYNC_FAILED");
        assertThat(interrupted.path("syncPhase").asText()).isEqualTo("UPDATE_POSTING");
        postBody(endpoint + "/sync", Map.of("revision", interrupted.path("revision").asInt()));
        JsonNode synced = await(endpoint, "knowledge", "SYNCED");
        assertThat(synced.path("remoteId").asText()).isEqualTo(target);
        assertThat(UPDATES.get()).isEqualTo(updates + 1);
        assertThat(original.get("content")).contains("Updated reviewed abstract", "Keep business-only section");
        assertThat(original.get("isStart")).isEqualTo("0");
        assertThat(BUSINESS_RECORDS.get(duplicate).get("name")).isEqualTo("Another matching business title");

        JsonNode versions = getBody(endpoint + "/sync-versions");
        assertThat(versions.path("total").asLong()).isEqualTo(1);
        JsonNode version = versions.path("rows").get(0);
        assertThat(version.path("operation").asText()).isEqualTo("UPDATE");
        assertThat(version.path("reviewer").asText()).isEqualTo("reviewer");
        JsonNode history = getBody(endpoint + "/sync-versions/" + version.path("id").asText());
        assertThat(history.path("before").path("content").asText()).contains("Old reviewed abstract");
        assertThat(history.path("after").path("content").asText()).contains("Updated reviewed abstract");
        assertThat(history.path("after").path("isStart").asText()).isEqualTo("0");

        JsonNode next = postBody(endpoint + "/revisions", Map.of("revision", synced.path("revision").asInt()));
        assertThat(next.path("updateTargetId").asText()).isEqualTo(target);
        String nextEndpoint = "/api/knowledge/" + next.path("id").asText();
        JsonNode nextPending = postBody(nextEndpoint + "/submit", Map.of("revision", next.path("revision").asInt()));
        JsonNode nextApproved = compareAndApprove(nextEndpoint, nextPending.path("revision").asInt(), "SKIP");
        postBody(nextEndpoint + "/sync", Map.of("revision", nextApproved.path("revision").asInt()));
        JsonNode skipped = await(nextEndpoint, "knowledge", "SYNCED");
        assertThat(skipped.path("syncPhase").asText()).isEqualTo("SKIPPED");
        assertThat(UPDATES.get()).isEqualTo(updates + 1);
        assertThat(getBody(nextEndpoint + "/sync-versions").path("total").asLong()).isEqualTo(1);
    }

    @Test
    void keepsEarlierPdfAndRestoresReferencesAfterAttachingNewOriginal() throws Exception {
        String source = "https://doi.org/10.1234/collection.pdf-version";
        String target = "existing-pdf-target";
        var original = businessRecord(target, "PDF version target", source);
        var body = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.get("content"));
        body.withObjectProperty("content").put("references", "<p>Keep existing reference citation</p>");
        original.put("content", body.toString());
        BUSINESS_RECORDS.put(target, original);
        BUSINESS_AFFIXES.put(target, List.of(OLD_VERSION_FILE));

        byte[] originalBytes = pdf();
        byte[] previousBytes = pdf();
        REMOTE_FILES.put(OLD_VERSION_FILE, previousBytes);
        JsonNode uploaded = body(mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file",
                        "synthetic.pdf", "application/pdf", originalBytes))
                .param("sourceUrl", source).param("title", "PDF version target")
                .with(user("collector").roles("ADMIN")).with(csrf())));
        String material = uploaded.path("materialId").asText();
        postBody("/api/materials/" + material + "/decision", Map.of("revision", 1, "approved", true,
                "sourceAbstract", "Revised PDF abstract only"));
        JsonNode draft = postBody("/api/materials/" + material + "/drafts", Map.of("typeCode", "31", "knowledgeClass", "17", "useAi", false));
        String endpoint = "/api/knowledge/" + draft.path("id").asText();
        var pending = postBody(endpoint + "/submit", Map.of("revision", draft.path("revision").asInt()));
        var review = postBody(endpoint + "/sync-review", Map.of("revision", pending.path("revision").asInt()));
        assertThat(review.path("differences").toString()).contains("PDF 原件", "SHA-256");
        var approved = approve(endpoint, review);
        postBody(endpoint + "/sync", Map.of("revision", approved.path("revision").asInt()));
        var synced = await(endpoint, "knowledge", "SYNCED");
        assertThat(synced.path("remoteId").asText()).isEqualTo(target);
        String versionFile = store.knowledge(draft.path("id").asText()).attachmentUrl();
        assertThat(versionFile).endsWith(Support.sha(originalBytes) + ".pdf");
        assertThat(BUSINESS_AFFIXES.get(target)).containsExactly(OLD_VERSION_FILE, versionFile);
        assertThat(REMOTE_FILES.get(OLD_VERSION_FILE)).isEqualTo(previousBytes);
        assertThat(original.get("content")).contains("Keep existing reference citation", versionFile,
                "Revised PDF abstract only").doesNotContain("PDF raw test sentence");

        var next = postBody(endpoint + "/revisions", Map.of("revision", synced.path("revision").asInt()));
        String nextEndpoint = "/api/knowledge/" + next.path("id").asText();
        var nextPending = postBody(nextEndpoint + "/submit", Map.of("revision", next.path("revision").asInt()));
        var nextApproved = compareAndApprove(nextEndpoint, nextPending.path("revision").asInt(), "SKIP");
        postBody(nextEndpoint + "/sync", Map.of("revision", nextApproved.path("revision").asInt()));
        assertThat(await(nextEndpoint, "knowledge", "SYNCED").path("syncPhase").asText()).isEqualTo("SKIPPED");
        assertThat(BUSINESS_AFFIXES.get(target)).hasSize(2);
    }

    @Test
    void checksNormalizedTitleInCategoryAgainBeforeCreating() throws Exception {
        String title = "Legacy journal title check";
        String source = "https://example.org/literature/legacy-title";
        JsonNode uploaded = body(mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file",
                        "legacy-title.txt", "text/plain", "Public article content".getBytes(StandardCharsets.UTF_8)))
                .param("sourceUrl", source).param("title", title).with(user("collector").roles("ADMIN")).with(csrf())));
        String material = uploaded.path("materialId").asText();
        postBody("/api/materials/" + material + "/decision", Map.of("revision", 1, "approved", true,
                "sourceAbstract", "Reviewed journal abstract"));
        var draft = postBody("/api/materials/" + material + "/drafts", Map.of("typeCode", "31", "knowledgeClass", "17", "useAi", false));
        String endpoint = "/api/knowledge/" + draft.path("id").asText();
        var pending = postBody(endpoint + "/submit", Map.of("revision", draft.path("revision").asInt()));
        var outside = businessRecord("outside-selected-category", title, source);
        outside.put("knowledgeClass", "170103");
        BUSINESS_RECORDS.put("outside-selected-category", outside);
        var approved = compareAndApprove(endpoint, pending.path("revision").asInt(), "CREATE");

        String target = "late-title-match";
        BUSINESS_RECORDS.put(target, businessRecord(target, "LEGACY-JOURNAL: TITLE CHECK!", source));
        int creates = CREATES.get();
        postBody(endpoint + "/sync", Map.of("revision", approved.path("revision").asInt()));
        var stale = await(endpoint, "knowledge", "PENDING");
        assertThat(stale.path("syncError").asText()).contains("出现了匹配知识");
        assertThat(CREATES.get()).isEqualTo(creates);
        var compared = postBody(endpoint + "/sync-review", Map.of("revision", stale.path("revision").asInt()));
        assertThat(compared.path("target").path("id").asText()).isEqualTo(target);
        assertThat(compared.path("target").path("basis").asText()).isEqualTo("类别及标题一致");
    }

    @Test
    void updatesManuallyMatchedLabKnowledgeWithNativeSubtypeAndAllDictionaryLinks() throws Exception {
        String id = "lab-target-audit";
        String doi = "https://doi.org/10.1234/outbound-lab-audit";
        var original = businessRecord(id,"Existing lab terminology","https://example.org/earlier-lab");
        original.put("type","8");
        original.put("childType","8-1");
        original.put("knowledgeClass","08");
        original.put("itemIds","lab-keep");
        var layout = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(original.get("content"));
        layout.withObjectProperty("content").put("references","<p>Original lab citation</p>");
        original.put("content",layout.toString());
        BUSINESS_RECORDS.put(id,original);
        BUSINESS_AFFIXES.put(id,List.of());
        JsonNode uploaded = body(mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile(
                "file","synthetic.pdf","application/pdf",pdf())).param("sourceUrl",doi).param("title","External lab evidence")
                .with(user("collector").roles("ADMIN")).with(csrf())));
        String material = uploaded.path("materialId").asText();
        postBody("/api/materials/"+material+"/decision",Map.of("revision",1,"approved",true,"sourceAbstract","Reviewed lab abstract"));
        var draft = postBody("/api/materials/"+material+"/drafts",Map.of("typeCode","8-1","useAi",false));
        String endpoint = "/api/knowledge/"+draft.path("id").asText();
        postBody(endpoint+"/submit",Map.of("revision",draft.path("revision").asInt()));
        var targets = getBody(endpoint+"/sync-targets?query=terminology");
        assertThat(targets.path("rows").get(0).path("id").asText()).isEqualTo(id);
        var review = postBody(endpoint+"/sync-review",Map.of("revision",currentRevision(endpoint),"manual",true,"targetId",id));
        assertThat(review.path("operation").asText()).isEqualTo("UPDATE");
        mvc.perform(post(endpoint+"/sync").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("revision",currentRevision(endpoint))))
                .with(user("reviewer").roles("REVIEWER")).with(csrf())).andExpect(status().isConflict());
        var approved = approve(endpoint,review);
        postBody(endpoint+"/sync",Map.of("revision",approved.path("revision").asInt()));
        var synced = await(endpoint,"knowledge","SYNCED");
        assertThat(synced.path("remoteId").asText()).isEqualTo(id);
        assertThat(original).containsEntry("childType","8-1").containsEntry("itemIds","lab-keep")
                .containsEntry("name","Existing lab terminology");
        assertThat(original.get("content")).contains("Original lab citation","Reviewed lab abstract");
        var receipt = getBody(endpoint+"/sync-receipt");
        assertThat(receipt.path("titleMatches").asBoolean() && receipt.path("pdfMatches").asBoolean()).isTrue();
        var versions = getBody(endpoint+"/sync-versions");
        var version = getBody(endpoint+"/sync-versions/"+versions.path("rows").get(0).path("id").asText());
        assertThat(version.path("after").path("itemIds")).isEqualTo(version.path("before").path("itemIds"));
        var next = postBody(endpoint+"/revisions",Map.of("revision",synced.path("revision").asInt()));
        assertThat(next.path("title").asText()).isEqualTo("Existing lab terminology");
    }

    private int currentRevision(String endpoint) throws Exception {
        return getBody(endpoint).path("knowledge").path("revision").asInt();
    }

    private JsonNode approve(String endpoint, JsonNode review) throws Exception {
        return postBody(endpoint + "/decision", Map.of("revision", currentRevision(endpoint), "approved", true,
                "reviewId", review.path("id").asText()));
    }

    private JsonNode compareAndApprove(String endpoint, int revision, String operation) throws Exception {
        JsonNode review = postBody(endpoint + "/sync-review", Map.of("revision", revision));
        assertThat(review.path("operation").asText()).isEqualTo(operation);
        return approve(endpoint, review);
    }

    private Map<String, String> businessRecord(String id, String title, String url) throws Exception {
        String content = WIRE.writeValueAsString(Map.of("catalog", List.of(Map.of("id", "abstract", "label", "摘要")),
                "properties", List.of(), "content", Map.of("abstract", "<p>Old reviewed abstract</p>",
                        "laiyuan", url, "customSection", "<p>Keep business-only section</p>")));
        return new ConcurrentHashMap<>(Map.of("id", id, "name", title, "type", "31", "knowledgeClass", "17",
                "remark", "existing import", "content", content, "isStart", "1"));
    }

    @Test
    void schedulesCollectionAndKeepsDuplicateRunProvenance() throws Exception {
        JsonNode task = body(mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("name", "Synthetic source collection", "mode", "URL",
                        "queryText", base() + "/source", "captureMode", "SINGLE", "resultLimit", 1,
                        "intervalMinutes", 60, "enabled", true))).with(user("collector").roles("ADMIN")).with(csrf())));
        String id = task.path("id").asText();
        store.db().sql("UPDATE mc_task SET next_run_at = :now WHERE id = :id").param("now", store.now() - 1000)
                .param("id", id).update();
        collection.schedule();
        String firstRun = getBody("/api/tasks/" + id).path("runs").path(0).path("id").asText();
        JsonNode first = await("/api/runs/" + firstRun, "run", "SUCCEEDED");
        assertThat(first.path("newCount").asInt()).isEqualTo(1);
        assertThat(first.path("actor").asText()).isEqualTo("scheduler");

        JsonNode second = body(mvc.perform(post("/api/tasks/{id}/runs", id).with(user("collector").roles("ADMIN")).with(csrf())));
        String secondRun = second.path("id").asText();
        assertThat(await("/api/runs/" + secondRun, "run", "SUCCEEDED").path("duplicateCount").asInt()).isEqualTo(1);
        String material = getBody("/api/runs/" + secondRun).path("results").path(0).path("materialId").asText();
        JsonNode detail = getBody("/api/materials/" + material);
        assertThat(detail.path("runs").size()).isEqualTo(2);
        assertThat(detail.path("material").path("sourceUrl").asText()).isEqualTo(base() + "/source");
        assertThat(detail.path("material").path("rawText").asText()).contains("Fixture article text");
        Path original = storedPath(material);
        assertThat(original.getFileName().toString()).isEqualTo("original.html");
        assertThat(Files.readString(original)).contains("<article>Fixture article text</article>");
        assertThat(Files.readString(original.resolveSibling("extracted.txt"))).contains("Fixture article text");
        assertThat(mvc.perform(get("/api/materials/{id}/original", material).with(user("reviewer").roles("REVIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray())
                .isEqualTo(Files.readAllBytes(original));
        postBody("/api/materials/" + material + "/decision", Map.of("revision", 1, "approved", true));
        postBody("/api/materials/" + material + "/drafts",
                Map.of("typeCode", "31", "knowledgeClass", "17", "useAi", true));
        assertThat(MODEL_REQUEST.get()).contains("Fixture article text");
    }

    @Test
    void managesMultiTargetPlansAndCollectsOnlySelectedSearchResults() throws Exception {
        String source = adminPost("/api/sources", Map.of("name", "Test source directory", "url", base() + "/source-a",
                "enabled", true, "accessState", "AUTHORIZED")).path("id").asText();
        long once = store.now() + Duration.ofMinutes(10).toMillis();
        JsonNode task = adminPost("/api/tasks", Map.of("name", "Multiple targets", "mode", "URL",
                "sourceIds", List.of(source), "urls", List.of(base() + "/source-b"), "captureMode", "SINGLE",
                "resultLimit", 5, "enabled", true, "scheduleMode", "ONCE", "scheduleAt", once));
        String id = task.path("id").asText();
        assertThat(task.path("nextRunAt").asLong()).isEqualTo(once);
        assertThat(getBody("/api/sources/" + source).path("tasks").path("total").asInt()).isEqualTo(1);
        for (boolean enabled : List.of(false, true)) {
            mvc.perform(put("/api/sources/" + source + "/state").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(Map.of("enabled", enabled)))
                    .with(user("collector").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
            assertThat(getBody("/api/sources/" + source).path("source").path("enabled").asBoolean()).isEqualTo(enabled);
        }
        assertThat(getBody("/api/sources/events").path("total").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(getBody("/api/tasks?query=Multiple&mode=URL").path("total").asInt()).isEqualTo(1);
        JsonNode started = adminPost("/api/tasks/" + id + "/runs", null);
        JsonNode done = await("/api/runs/" + started.path("id").asText(), "run", "SUCCEEDED");
        assertThat(done.path("newCount").asInt()).isEqualTo(2);
        assertThat(getBody("/api/tasks/" + id).path("task").path("nextRunAt").asLong()).isEqualTo(once);

        store.db().sql("UPDATE mc_task SET next_run_at = :now WHERE id = :id")
                .params(Map.of("now", store.now() - 1, "id", id)).update();
        collection.schedule();
        JsonNode scheduled = getBody("/api/tasks/" + id).path("runs").path(0);
        assertThat(scheduled.path("actor").asText()).isEqualTo("scheduler");
        await("/api/runs/" + scheduled.path("id").asText(), "run", "SUCCEEDED");
        assertThat(getBody("/api/tasks/" + id).path("task").path("nextRunAt").isNull()).isTrue();

        JsonNode web = adminPost("/api/tasks", Map.of("name", "Select search results", "mode", "WEB", "queryText", "test",
                "captureMode", "SINGLE", "resultLimit", 5, "enabled", true, "intervalMinutes", 0, "selectResults", true));
        JsonNode searched = adminPost("/api/tasks/" + web.path("id").asText() + "/runs", null);
        String search = "/api/runs/" + searched.path("id").asText();
        assertThat(await(search, "run", "SUCCEEDED").path("newCount").asInt()).isZero();
        var candidates = getBody(search).path("results");
        assertThat(candidates).hasSize(2);
        assertThat(candidates.path(0).path("status").asText()).isEqualTo("CANDIDATE");
        assertThat(candidates.path(0).path("materialId").isNull()).isTrue();
        mvc.perform(post(search + "/capture").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("resultIds", List.of("foreign-candidate"))))
                .with(user("collector").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        JsonNode capture = adminPost(search + "/capture", Map.of("resultIds", List.of(candidates.path(0).path("id").asText())));
        JsonNode captured = await("/api/runs/" + capture.path("id").asText(), "run", "SUCCEEDED");
        assertThat(captured.path("parentRunId").asText()).isEqualTo(searched.path("id").asText());
        assertThat(captured.path("newCount").asInt()).isEqualTo(1);
    }

    @Test
    void persistsClassificationFiltersAndAppliesVersionedTemplateRules() throws Exception {
        String classId = adminPost("/api/classifications", Map.of("groupCode", "MATERIAL", "name", "回归资料类型",
                "enabled", true, "displayOrder", 10)).path("id").asText();
        JsonNode uploaded = body(mvc.perform(multipart("/api/materials/upload")
                .file(new MockMultipartFile("file", "filter-fixture.txt", "text/plain", "Fixture review text".getBytes(StandardCharsets.UTF_8)))
                .with(user("collector").roles("ADMIN")).with(csrf())));
        String material = uploaded.path("materialId").asText();
        mvc.perform(put("/api/materials/classifications").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("groupCode", "MATERIAL", "materialIds", List.of(material),
                        "classIds", List.of(classId))))
                .with(user("reviewer").roles("REVIEWER")).with(csrf())).andExpect(status().isOk());
        assertThat(getBody("/api/materials?classId=" + classId).path("total").asInt()).isEqualTo(1);
        assertThat(getBody("/api/materials/" + material).path("classificationIds").path(0).asText()).isEqualTo(classId);

        String profile = "/api/template-profiles/31?knowledgeClass=17";
        var base = getBody(profile);
        String summaryField = base.path("summaryKey").asText();
        var first = body(mvc.perform(put(profile).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(
                Map.of("revision", 0, "rules", Map.of(summaryField, Map.of("instruction", "Fixture summary extraction rule", "required", true)))))
                .with(user("collector").roles("ADMIN")).with(csrf())));
        assertThat(first.path("profileRevision").asInt()).isEqualTo(1);
        postBody("/api/materials/" + material + "/decision", Map.of("revision", 1, "approved", true));
        var draft = postBody("/api/materials/" + material + "/drafts",
                Map.of("typeCode", "31", "knowledgeClass", "17", "useAi", true));
        assertThat(MODEL_REQUEST.get()).contains("Fixture summary extraction rule");
        assertThat(draft.path("schema").path("profileRevision").asInt()).isEqualTo(1);
        body(mvc.perform(put(profile).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(
                Map.of("revision", 1, "rules", Map.of())))
                .with(user("collector").roles("ADMIN")).with(csrf())));
        assertThat(getBody("/api/knowledge/" + draft.path("id").asText()).path("knowledge").path("schema")
                .path("profileRevision").asInt()).isEqualTo(1);
    }

    @Test
    void tracksScopedTasksOriginalsAndReviewAcrossTheWorkbench() throws Exception {
        String root = adminPost("/api/classifications", Map.of("groupCode", "SUBJECT", "name", "Fixture specialty",
                "enabled", true, "displayOrder", 18)).path("id").asText();
        String child = adminPost("/api/classifications", Map.of("groupCode", "SUBJECT", "name", "Fixture disease",
                "parentId", root, "enabled", true, "displayOrder", 10)).path("id").asText();
        String task = adminPost("/api/tasks", Map.of("name", "Scoped upload", "mode", "UPLOAD",
                "enabled", true, "captureMode", "SINGLE", "resultLimit", 1)).path("id").asText();
        assertThat(getBody("/api/tasks?query=Scoped&state=WAITING").path("total").asInt()).isEqualTo(1);
        String material = body(mvc.perform(multipart("/api/materials/upload")
                .file(new MockMultipartFile("file", "scoped.txt", "text/plain", "Scoped checklist".getBytes(StandardCharsets.UTF_8)))
                .param("taskId", task)
                .with(user("collector").roles("ADMIN")).with(csrf()))).path("materialId").asText();
        mvc.perform(put("/api/materials/classifications").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("groupCode", "SUBJECT", "materialIds", List.of(material),
                        "classIds", List.of(child)))).with(user("reviewer").roles("REVIEWER")).with(csrf())).andExpect(status().isOk());
        for (String id : List.of(root, child)) {
            assertThat(getBody("/api/dashboard?subjectId=" + id).path("unparsed").asInt()).isOne();
            JsonNode result = getBody("/api/tasks?state=SUCCEEDED&subjectId=" + id).path("rows").get(0);
            assertThat(result.path("id").asText()).isEqualTo(task);
            assertThat(result.path("mode").asText()).isEqualTo("UPLOAD");
            assertThat(result.path("latestRun").path("newCount").asInt()).isOne();
            var rows = getBody("/api/materials?acquireMode=UPLOAD&parsed=false&subjectId=" + id).path("rows");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).path("classIds").toString()).contains(root,child);
        }
        assertThat(getBody("/api/tasks?untilTime=1&subjectId=" + child).path("total").asInt()).isZero();
        postBody("/api/materials/" + material + "/decision", Map.of("revision", 1, "approved", true));
        String draftUrl = "/api/materials/" + material + "/drafts";
        String requestId = java.util.UUID.randomUUID().toString();
        var input = Map.of("typeCode", "1", "useAi", false, "requestId", requestId);
        var draft = postBody(draftUrl, input);
        assertThat(postBody(draftUrl, input).path("id").asText()).isEqualTo(draft.path("id").asText());
        mvc.perform(post(draftUrl).with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("typeCode","6","useAi",false,"requestId",requestId))))
                .andExpect(status().isConflict());
        assertThat(getBody("/api/dashboard?subjectId=" + child).path("unparsed").asInt()).isZero();
        assertThat(getBody("/api/materials?subjectId=" + root + "&parsed=true").path("rows").get(0).path("knowledgeCount").asInt()).isOne();
        var review = getBody("/api/knowledge?human=DRAFT&sync=UNSYNCED&subjectId=" + root).path("rows");
        assertThat(review).hasSize(1);
        assertThat(review.get(0).path("id").asText()).isEqualTo(draft.path("id").asText());
        assertThat(getBody("/api/knowledge?human=APPROVED&subjectId=" + root).path("total").asInt()).isZero();
        postBody(draftUrl, Map.of("typeCode","6","useAi",false,"requestId",java.util.UUID.randomUUID().toString()));
        assertThat(getBody("/api/materials?subjectId=" + child).path("rows").get(0).path("knowledgeCount").asInt()).isEqualTo(2);
        assertThat(getBody("/api/knowledge?materialId=" + material).path("rows")).hasSize(2);
        String peer = adminPost("/api/classifications", Map.of("groupCode","SUBJECT","name","Second fixture",
                "parentId",root,"enabled",true,"displayOrder",20)).path("id").asText();
        var shift = put("/api/classifications/" + peer + "/position").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsBytes(Map.of("peerId",child,"up",true,"revision",1)));
        mvc.perform(shift.with(user("collector").roles("ADMIN")).with(csrf())).andExpect(status().isOk());
        mvc.perform(shift.with(user("collector").roles("ADMIN")).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void collectsFiftyDistinctCandidatesAcrossSearchPages() throws Exception {
        var task = adminPost("/api/tasks", Map.of("name", "Paged search", "mode", "WEB", "queryText", "paged-fixture",
                "enabled", true, "captureMode", "SINGLE", "resultLimit", 50, "selectResults", true));
        String run = adminPost("/api/tasks/" + task.path("id").asText() + "/runs", null).path("id").asText();
        await("/api/runs/" + run, "run", "SUCCEEDED");
        assertThat(getBody("/api/runs/" + run).path("results")).hasSize(50);
        assertThat(getBody("/api/tasks?query=Paged").path("rows").get(0).path("latestRun").path("candidateCount").asInt()).isEqualTo(50);
    }

    @Test
    void synchronizesReferenceDataAndVerifiesBusinessSelections() throws Exception {
        mvc.perform(post("/api/business-catalog/refresh").with(user("reader").roles("REVIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
        var state=adminPost("/api/business-catalog/refresh",Map.of());
        assertThat(state.path("specialties").asInt()).isOne();
        assertThat(state.path("diseases").asInt()).isOne();
        var nodes=getBody("/api/classifications");
        assertThat(nodes.findValuesAsText("remoteKey")).contains("SUBJECT:1701","SUBJECT:170103","DOMAIN:31");
        assertThat(getBody("/api/categories/1/classes").findValuesAsText("code")).contains("01").doesNotContain("17");
        var stable=store.db().sql("SELECT id FROM mc_classification WHERE remote_key='SUBJECT:170103'").query(String.class).single();
        assertThat(stable).isEqualTo("subject-hypertension");
        adminPost("/api/business-catalog/refresh",Map.of());
        assertThat(store.db().sql("SELECT revision FROM mc_classification WHERE id=:id").param("id",stable)
                .query(Integer.class).single()).isEqualTo(2);
        referenceFailure=true;
        try {
            mvc.perform(post("/api/business-catalog/refresh").with(user("collector").roles("ADMIN")).with(csrf()))
                    .andExpect(status().isBadGateway());
            assertThat(getBody("/api/business-catalog").path("diseases").asInt()).isOne();
        } finally {referenceFailure=false;}
        var options=getBody("/api/business-catalog/relations/1/icd10Ids?query=Test");
        assertThat(options.path("rows").get(0).path("value").asText()).isEqualTo("fixture-icd");
        assertThat(getBody("/api/business-catalog/parameters/YES_NOT_DICT").get(0).path("value").asText()).isEqualTo("1");
        var added=body(mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file","relation.txt","text/plain",
                "Dictionary verification".getBytes(StandardCharsets.UTF_8))).with(user("collector").roles("ADMIN")).with(csrf())));
        String source=added.path("materialId").asText();
        postBody("/api/materials/"+source+"/decision",Map.of("revision",1,"approved",true,"sourceAbstract","Reviewed source"));
        var draft=postBody("/api/materials/"+source+"/drafts",Map.of("typeCode","1","useAi",false));
        String path="/api/knowledge/"+draft.path("id").asText();
        for (String rel:List.of("missing-icd","fixture-icd")) {
            var payload=Map.of("revision",1,"title","Test","summary","Reviewed","fields",Map.of("heredity","1"),
                    "relations",Map.of("icd10Ids",List.of(rel)));
            var req=put(path).with(user("reviewer").roles("REVIEWER")).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsBytes(payload));
            if(rel.startsWith("missing")) mvc.perform(req).andExpect(status().isBadRequest());
            else assertThat(body(mvc.perform(req)).path("relations").path("icd10Ids").get(0).asText()).isEqualTo(rel);
        }
    }

    @Test
    void collectsBusinessLiteratureThroughTheNormalRunAndKeepsReviewPending() throws Exception {
        var detail=getBody("/api/business-literature/"+LIBRARY_ARTICLE);
        assertThat(detail.path("summary").asText()).isEqualTo("Source abstract");
        assertThat(detail.path("attachments").get(0).path("name").asText()).isEqualTo("journal.txt");
        var request="/api/business-literature/"+LIBRARY_ARTICLE+"/collect";
        mvc.perform(post(request).contentType(MediaType.APPLICATION_JSON).content("{\"attachmentId\":\"wrong\"}")
                .with(user("collector").roles("ADMIN")).with(csrf())).andExpect(status().isBadRequest());
        for(int attempt=0;attempt<2;attempt++) {
            var run=adminPost(request,Map.of("attachmentId","journal-file"));
            var finished=await("/api/runs/"+run.path("id").asText(),"run","SUCCEEDED");
            assertThat(finished.path(attempt==0?"newCount":"duplicateCount").asInt()).isOne();
            var result=getBody("/api/runs/"+run.path("id").asText()).path("results").get(0);
            var material=store.material(result.path("materialId").asText());
            assertThat(material.externalRef()).startsWith("CDSS:31:"+LIBRARY_ARTICLE+":170103");
            assertThat(material.status()).isEqualTo(Records.MaterialStatus.PENDING);
            assertThat(store.bytes(material)).isEqualTo("Native literature".getBytes(StandardCharsets.UTF_8));
        }
    }

    private JsonNode adminPost(String endpoint, Object payload) throws Exception {
        var request = post(endpoint).with(user("collector").roles("ADMIN")).with(csrf());
        if (payload != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload));
        return body(mvc.perform(request));
    }

    private Path storedPath(String materialId) {
        return originals.resolve(store.db().sql("SELECT original_path FROM mc_original WHERE material_id = :id")
                .param("id", materialId).query(String.class).single());
    }

    private JsonNode await(String endpoint, String key, String state) throws Exception {
        long expires = System.nanoTime() + Duration.ofSeconds(12).toNanos();
        JsonNode current;
        do {
            current = getBody(endpoint).path(key);
            if (state.equals(current.path("status").asText())) return current;
            Thread.sleep(60);
        } while (System.nanoTime() < expires);
        throw new AssertionError("Expected status " + state + ", received " + current);
    }

    private JsonNode postBody(String endpoint, Object payload) throws Exception {
        return body(mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(payload))
                .with(user("reviewer").roles("REVIEWER")).with(csrf())));
    }

    private JsonNode getBody(String endpoint) throws Exception {
        return body(mvc.perform(get(endpoint).with(user("reviewer").roles("REVIEWER"))));
    }

    private JsonNode body(ResultActions action) throws Exception {
        return json.readTree(action.andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
    }

    private byte[] pdf() throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (var text = new PDPageContentStream(document, page)) {
                text.beginText();
                text.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                text.newLineAtOffset(35, 730);
                text.showText("PDF raw test sentence");
                text.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    @Test
    void retriesShortLivedSourceOutages() throws Exception {
        SOURCE_ATTEMPTS.set(0);
        assertThat(sourceHttp.get(base() + "/source-transient").bytes()).isNotEmpty();
        assertThat(SOURCE_ATTEMPTS.get()).isEqualTo(2);
        assertThat(SourceHttp.retryDelay("120")).isEqualTo(120_000);
        assertThat(SourceHttp.retryDelay("invalid")).isNegative();
    }

    @Test
    void matchesLegacyManualByAliasWithinSubtypeAndPreservesQualifiers() throws Exception {
        var uploaded = body(mvc.perform(multipart("/api/materials/upload").file(new MockMultipartFile("file",
                "lab-alias.txt", "text/plain", "项目说明及资料".getBytes(StandardCharsets.UTF_8)))
                .param("sourceUrl", "https://example.org/manual-alias").param("title", "血清谷草转氨酶测定")
                .with(user("collector").roles("ADMIN")).with(csrf())));
        String material = uploaded.path("materialId").asText();
        postBody("/api/materials/"+material+"/decision",Map.of("revision",1,"approved",true,"sourceAbstract","项目摘要"));
        var draft = postBody("/api/materials/"+material+"/drafts",Map.of("typeCode","8-1","knowledgeClass","08","useAi",false));
        String endpoint = "/api/knowledge/"+draft.path("id").asText();
        for (String id : List.of("alias-legacy","alias-wrong-subtype","alias-plasma")) {
            String name = (id.equals("alias-plasma") ? "血浆" : "血清")+"天冬氨酸氨基转移酶检测";
            var original = businessRecord(id, name, "https://example.org/earlier-lab");
            original.put("type","8");
            original.put("childType",id.equals("alias-wrong-subtype") ? "8-2" : "8-1");
            original.remove("knowledgeClass");
            BUSINESS_RECORDS.put(id,original);
            BUSINESS_AFFIXES.put(id,List.of());
        }
        postBody(endpoint+"/submit",Map.of("revision",draft.path("revision").asInt()));
        var review = postBody(endpoint+"/sync-review",Map.of("revision",currentRevision(endpoint)));
        assertThat(review.path("operation").asText()).isEqualTo("UPDATE");
        assertThat(review.path("candidates")).hasSize(1);
        assertThat(review.path("target").path("id").asText()).isEqualTo("alias-legacy");
        assertThat(review.path("differences").findValuesAsText("field")).doesNotContain("标题");
    }

    private static String base() { return "http://127.0.0.1:" + REMOTE.getAddress().getPort(); }

    private static HttpServer remote() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try (exchange) {
                    String path = exchange.getRequestURI().getPath();
                    byte[] request = exchange.getRequestBody().readAllBytes();
                    if (path.startsWith("/source")) {
                        if (path.equals("/source-transient") && SOURCE_ATTEMPTS.incrementAndGet() == 1) {
                            exchange.getResponseHeaders().set("Retry-After","0");
                            exchange.sendResponseHeaders(503, -1);
                            return;
                        }
                        byte[] page = "<html><head><title>Fixture article</title></head><body><article>Fixture article text</article></body></html>"
                                .getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "text/html;charset=utf-8");
                        exchange.sendResponseHeaders(200, page.length);
                        exchange.getResponseBody().write(page);
                        return;
                    }
                    if (path.equals("/api/upload/view/knowledge-affix/journal.txt")) {
                        byte[] bytes="Native literature".getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);return;
                    }
                    if (path.startsWith("/api/upload/view/") && REMOTE_FILES.containsKey(path.substring(17))) {
                        byte[] bytes = REMOTE_FILES.get(path.substring(17));
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        return;
                    }
                    int code = 200;
                    Object response = switch (path) {
                        case "/api/login" -> ok(Map.of("access_token", "fixture-token", "expires_in", 600));
                        case "/search" -> {
                            String query = exchange.getRequestURI().getQuery();
                            if (query.contains("paged-fixture")) {
                                int page = Integer.parseInt(query.substring(query.lastIndexOf("pageno=") + 7));
                                yield Map.of("results", java.util.stream.IntStream.range(0,20)
                                        .mapToObj(i -> Map.of("title", "Page item " + i, "url", base() + "/source-" + (page * 19 + i))).toList());
                            }
                            yield Map.of("results", List.of(Map.of("title","A fixture","url",base() + "/source-select-a"),
                                    Map.of("title","B fixture","url",base() + "/source-select-b")));
                        }
                        case "/api/parameter/find-by-name" -> {
                            try (var in = WorkflowIntegrationTest.class.getResourceAsStream("/cdss/knowledge-templates.json")) {
                                yield ok(Map.of("parameterValue", new String(in.readAllBytes(), StandardCharsets.UTF_8)));
                            }
                        }
                        case "/api/knowledge/get-statistics-by-status" -> ok(List.of(Map.of("itemClass","1","itemName","疾病知识"),
                                Map.of("itemClass","8","childType","8-1","itemName","检验知识"),
                                Map.of("itemClass","6","itemName","药品说明书"),Map.of("itemClass","17","itemName","临床指南")));
                        case "/api/knowledge/get-class-tree" -> ok(referenceFailure?List.of():List.of(
                                Map.of("knowledgeClass","01","knowledgeClassName","疾病","upperClass","00"),
                                Map.of("knowledgeClass","08","knowledgeClassName","检验检查","upperClass","00"),
                                Map.of("knowledgeClass","17","knowledgeClassName","临床指南知识","upperClass","00"),
                                Map.of("knowledgeClass","1701","knowledgeClassName","心血管内科","upperClass","17"),
                                Map.of("knowledgeClass","170103","knowledgeClassName","高血压","upperClass","1701")));
                        case "/api/dict/findMapPage" -> ok(List.of(Map.of("code","1","name","是","id","fixture-one","validFlag",1)));
                        case "/api/knowledge-dict/icd10/query" -> Map.of("code",0,"success",true,"total",1,"data",
                                List.of(Map.of("id","fixture-icd","name","Test condition","code","T01","source","国家临床版2.0","validFlag",1)));
                        case "/api/knowledge-dict/icd10/find-by-id" -> ok("fixture-icd".equals(form(request).get("id"))?
                                Map.of("id","fixture-icd","name","Test condition","code","T01","source","国家临床版2.0","validFlag",1):Map.of());
                        case "/v1/chat/completions" -> {
                            MODEL_REQUEST.set(new String(request, StandardCharsets.UTF_8));
                            if (EMPTY_ANALYSIS.compareAndSet(1,0)) {
                                yield Map.of("choices",List.of(Map.of("finish_reason","stop","message",Map.of("content","{}"))));
                            }
                            if (MODEL_REQUEST.get().contains("findings")) {
                                yield Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                                        Map.of("content", "{\"findings\":[\"核对原文所述研究对象和限定条件\"]}"))));
                            }
                            yield Map.of("choices", java.util.List.of(Map.of("finish_reason", "stop", "message",
                                    Map.of("content", "{\"title\":\"Synthetic PDF\",\"summary\":\"Synthetic reviewed abstract\","
                                            + "\"fields\":{\"mainBody\":\"unapprovedMainBody\"}}"))));
                        }
                        case "/api/knowledge/save" -> {
                            var fields = form(request);
                            String id = fields.get("id");
                            if (id != null) {
                                var target = BUSINESS_RECORDS.get(id);
                                if ("8".equals(target.get("type"))) {
                                    if (!target.get("childType").equals(fields.get("childType"))) yield Map.of("code",1,"success",false,"message","缺少匹配的检验检查子类");
                                    target.put("itemIds",fields.getOrDefault("itemIds[0]",""));
                                }
                                UPDATES.incrementAndGet();
                                target.putAll(fields);
                                if ("1".equals(target.get("isStart"))) target.put("isStart", "0");
                                if (id.equals(INTERRUPT_UPDATE.getAndSet(null))) {
                                    code = 502;
                                    yield Map.of("message", "Response interrupted after update");
                                }
                                yield ok(target);
                            }
                            CREATES.incrementAndGet();
                            SAVED_FORM.set(fields);
                            code = 502;
                            yield Map.of("message", "Response interrupted after commit");
                        }
                        case "/api/knowledge/query" -> queryBusiness(form(request));
                        case "/api/knowledge/find-by-id" -> {
                            String id = form(request).get("id");
                            if(LIBRARY_ARTICLE.equals(id)) yield ok(Map.of("id",id,"type","31","knowledgeClass","170103",
                                    "validFlag",1,"name","Native journal","content",Map.of("content",Map.of("abstract","Source abstract"))));
                            var found = TARGET_ID.equals(id) ? saved() : BUSINESS_RECORDS.get(id);
                            if (found != null && "8".equals(found.get("type"))) {
                                var nativeItem = new LinkedHashMap<String,Object>(found);
                                String item = found.getOrDefault("itemIds","");
                                nativeItem.put("itemIds",item.isBlank() ? List.of() : List.of(item));
                                yield ok(nativeItem);
                            }
                            yield ok(found);
                        }
                        case "/api/upload" -> {
                            String multipart = new String(request,StandardCharsets.ISO_8859_1);
                            var name = java.util.regex.Pattern.compile("filename=\\\"([^\\\"]+)\\\"").matcher(multipart);
                            if (!name.find()) throw new AssertionError("Missing original filename");
                            String storage = "knowledgeAffix/MedCollect/" + name.group(1);
                            int start = multipart.indexOf("\r\n\r\n",name.end()) + 4;
                            int end = multipart.lastIndexOf("\r\n--");
                            REMOTE_FILES.put(storage, Arrays.copyOfRange(request,start,end));
                            FILE_WRITES.computeIfAbsent(storage,k -> new AtomicInteger()).incrementAndGet();
                            yield ok(Map.of("url",storage));
                        }
                        case "/api/knowledge/query-affix" -> {
                            String id = form(request).get("knowledgeId");
                            if(LIBRARY_ARTICLE.equals(id)) yield ok(List.of(Map.of("id","journal-file","knowledgeAffix","knowledge-affix/journal.txt","affixName","journal.txt")));
                            var urls = TARGET_ID.equals(id) ? List.copyOf(AFFIXES.values()) : BUSINESS_AFFIXES.getOrDefault(id, List.of());
                            yield ok(urls.stream().map(url -> Map.of("knowledgeAffix", url)).toList());
                        }
                        case "/api/knowledge/save-affix" -> {
                            var fields = form(request);
                            String id = fields.get("knowledgeId");
                            if (BUSINESS_RECORDS.containsKey(id)) {
                                var urls = new ArrayList<>(BUSINESS_AFFIXES.get(id));
                                urls.add(fields.get("knowledgeAffix"));
                                BUSINESS_AFFIXES.put(id, urls);
                                var record = BUSINESS_RECORDS.get(id);
                                var body = (com.fasterxml.jackson.databind.node.ObjectNode) WIRE.readTree(record.get("content"));
                                body.withObjectProperty("content").put("references", urls.stream()
                                        .map(url -> "<p><a href=\"/api/upload/view/" + url + "?preview=1\">PDF</a></p>")
                                        .collect(Collectors.joining()));
                                record.put("content", body.toString());
                                yield ok(fields);
                            }
                            BINDS.incrementAndGet();
                            AFFIXES.put(fields.get("id"), fields.get("knowledgeAffix"));
                            code = 502;
                            yield Map.of("message", "Response interrupted after binding");
                        }
                        default -> {
                            code = 404;
                            yield Map.of("path", path);
                        }
                    };
                    byte[] payload = WIRE.writeValueAsBytes(response);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(code, payload.length);
                    exchange.getResponseBody().write(payload);
                }
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, Object> ok(Object data) { return Map.of("code", 0, "success", true, "data", data); }

    private static Map<String, String> saved() {
        var result = new LinkedHashMap<>(SAVED_FORM.get());
        result.put("id", TARGET_ID);
        return result;
    }

    private static Map<String, Object> queryBusiness(Map<String, String> filters) {
        var rows = new ArrayList<>(BUSINESS_RECORDS.values());
        if (SAVED_FORM.get() != null) rows.add(saved());
        var matching = rows.stream().filter(row -> row.get("type").equals(filters.get("type")))
                .filter(row -> filters.get("knowledgeClass") == null || filters.get("knowledgeClass").equals(row.get("knowledgeClass")))
                .filter(row -> row.get("name").toLowerCase(java.util.Locale.ROOT)
                        .contains(filters.getOrDefault("name", "").toLowerCase(java.util.Locale.ROOT))).toList();
        return Map.of("code", 0, "success", true, "data", matching, "total", matching.size());
    }

    private static Map<String, String> form(byte[] body) {
        return Arrays.stream(new String(body, StandardCharsets.UTF_8).split("&")).map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(parts -> URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                        parts -> URLDecoder.decode(parts.length == 1 ? "" : parts[1], StandardCharsets.UTF_8)));
    }
}
