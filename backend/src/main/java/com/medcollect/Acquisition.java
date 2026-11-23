package com.medcollect;

import static com.medcollect.DocumentReader.*;
import static com.medcollect.Records.*;
import static com.medcollect.Support.*;

import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.springframework.http.ContentDisposition;
import org.springframework.stereotype.Component;

@Component
class Acquisition {
    static final String EUROPE_PMC = "https://www.ebi.ac.uk/europepmc/webservices/rest/search";
    private final SourceHttp http;
    private final DocumentReader reader;
    private final ObjectMapper json;
    private final AppSettings settings;
    private final Clock clock;

    record Candidate(String title, String url, JsonNode article) {}
    record Result(Acquired material, String note) {}

    Acquisition(SourceHttp http, DocumentReader reader, ObjectMapper json, AppSettings settings, Clock clock) {
        this.http = http;
        this.reader = reader;
        this.json = json;
        this.settings = settings;
        this.clock = clock;
    }

    List<Candidate> discover(Task task, Source source, String target) throws IOException {
        return switch (task.collectionMode()) {
            case URL -> fromUrl(task, target);
            case EUROPE_PMC -> fromLiterature(task);
            case WEB -> fromWeb(task, source);
            case UPLOAD -> throw bad("手工上传任务请选择原件文件");
        };
    }

    private List<Candidate> fromUrl(Task task, String target) throws IOException {
        String url = httpUri(target).toString();
        if (task.captureMode() == CaptureMode.SINGLE) return List.of(new Candidate(null, url, null));

        SourceHttp.Download page = http.get(url);
        if (!trim(page.contentType()).contains("html")) throw bad("栏目采集需要一个可读取的网页地址");
        var document = reader.html(page.bytes(), page.uri().toString(), page.contentType());
        var links = new LinkedHashMap<String, Candidate>();
        var section = document.selectFirst("article, main, [role=main]");
        for (var link : (section == null ? document : section).select("a[href]")) {
            if (links.size() >= task.resultLimit()) break;
            String raw = link.absUrl("href");
            if (raw.isBlank()) continue;
            try {
                URI uri = httpUri(raw);
                if (!uri.getHost().equalsIgnoreCase(page.uri().getHost()) || uri.equals(page.uri())) continue;
                boolean file = uri.getPath().matches("(?i).*\\.(pdf|docx|txt|xlsx?|csv)$");
                if (file || !link.text().isBlank()) {
                    links.putIfAbsent(uri.toString(), new Candidate(link.text(), uri.toString(), null));
                }
            } catch (org.springframework.web.server.ResponseStatusException ignored) {
                // Skip navigation links which do not point to documents or pages.
            }
        }
        if (links.isEmpty()) throw bad("栏目未发现可采集的站内链接");
        return new ArrayList<>(links.values());
    }

    private List<Candidate> fromLiterature(Task task) throws IOException {
        String query = "(" + task.queryText() + ") AND FIRST_PDATE:[1800-01-01 TO " + LocalDate.now(clock) + "]";
        if (task.intervalMinutes() > 0) query += " sort_date:y";
        JsonNode response = json.readTree(http.get(EUROPE_PMC + "?query=" + encode(query)
                + "&format=json&resultType=core&pageSize=" + task.resultLimit()).bytes());
        var results = new ArrayList<Candidate>();
        for (JsonNode article : response.path("resultList").path("result")) {
            String source = article.path("source").asText();
            String recordId = article.path("id").asText();
            if (!source.matches("[A-Z0-9]+") || !recordId.matches("[A-Za-z0-9_.:-]+")) continue;
            String url = "https://europepmc.org/article/" + source + "/" + recordId;
            results.add(new Candidate(article.path("title").asText(), url, article));
            if (results.size() >= task.resultLimit()) break;
        }
        return results;
    }

    private List<Candidate> fromWeb(Task task, Source source) throws IOException {
        if (trim(settings.searxUrl()).isBlank()) throw bad("全网搜索需要配置 SearXNG 服务");
        String query = task.queryText();
        if (source != null) query += " site:" + httpUri(source.url()).getHost();
        String endpoint = baseUrl(settings.searxUrl());
        if (!endpoint.endsWith("/search")) endpoint += "/search";
        JsonNode response = json.readTree(http.get(endpoint + "?format=json&q=" + encode(query)).bytes());
        var results = new LinkedHashMap<String, Candidate>();
        for (JsonNode item : response.path("results")) {
            try {
                URI url = httpUri(item.path("url").asText());
                if (source != null) {
                    String host = httpUri(source.url()).getHost();
                    if (!url.getHost().equalsIgnoreCase(host) && !url.getHost().toLowerCase(java.util.Locale.ROOT)
                            .endsWith("." + host.toLowerCase(java.util.Locale.ROOT))) continue;
                }
                results.putIfAbsent(url.toString(), new Candidate(item.path("title").asText(), url.toString(), null));
            } catch (org.springframework.web.server.ResponseStatusException ignored) {
                continue;
            }
            if (results.size() >= task.resultLimit()) break;
        }
        return new ArrayList<>(results.values());
    }

    Result acquire(Candidate candidate, Source source) throws IOException {
        if (candidate.article() != null) return acquireArticle(candidate);
        SourceHttp.Download download = http.get(candidate.url());
        String fileName = nameFrom(download.uri());
        try {
            fileName = withDefault(ContentDisposition.parse(download.disposition()).getFilename(), fileName);
        } catch (IllegalArgumentException ignored) {
            // The final URL still provides a usable name.
        }
        return new Result(reader.read(download.bytes(), fileName, download.contentType(),
                candidate.url(), download.uri().toString(), source == null ? download.uri().getHost() : source.name(),
                candidate.title()), null);
    }

    private Result acquireArticle(Candidate candidate) throws IOException {
        JsonNode article = candidate.article();
        String authors = article.path("authorString").asText();
        String publisher = article.path("journalInfo").path("journal").path("title").asText();
        String date = article.path("firstPublicationDate").asText();
        String ref = withDefault(article.path("doi").asText(), article.path("id").asText());
        String summary = limit(Jsoup.parse(article.path("abstractText").asText()).text(), 12000);
        String title = limit(Jsoup.parse(candidate.title()).text(), 400);
        String note = "已保存检索元数据和来源，未提供开放获取 PDF";
        if ("Y".equals(article.path("isOpenAccess").asText())) {
            for (JsonNode link : article.path("fullTextUrlList").path("fullTextUrl")) {
                if (!"pdf".equalsIgnoreCase(link.path("documentStyle").asText())
                        || !("OA".equalsIgnoreCase(link.path("availabilityCode").asText())
                        || "Open access".equalsIgnoreCase(link.path("availability").asText()))) continue;
                try {
                    SourceHttp.Download download = http.get(link.path("url").asText());
                    Acquired pdf = reader.read(download.bytes(), article.path("id").asText() + ".pdf",
                            "application/pdf", candidate.url(), download.uri().toString(), "Europe PMC", title);
                    return new Result(new Acquired(pdf.title(), pdf.formatCode(), pdf.fileName(), pdf.mimeType(),
                            pdf.sourceUrl(), pdf.fetchedUrl(), pdf.sourceName(), authors, publisher, date, ref,
                            withDefault(summary, pdf.sourceAbstract()), null, pdf.bytes()), null);
                } catch (IOException | org.springframework.web.server.ResponseStatusException e) {
                    note = "开放获取 PDF 暂不可下载，已保存检索元数据和摘要";
                }
            }
        }
        byte[] metadata = json.writeValueAsBytes(article);
        return new Result(new Acquired(title, "METADATA", safeName(article.path("id").asText()) + ".json",
                "application/json", candidate.url(), EUROPE_PMC, "Europe PMC", authors, publisher, date,
                ref, summary, null, metadata), note);
    }
}
