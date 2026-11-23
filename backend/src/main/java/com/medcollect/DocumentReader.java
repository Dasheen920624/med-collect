package com.medcollect;

import static com.medcollect.Support.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
class DocumentReader {
    static final int MAX_BYTES = 20 * 1024 * 1024;
    private static final int MAX_TEXT = 180_000;
    private static final Pattern ABSTRACT = Pattern.compile(
            "(?is)(?:^|\\R)\\h*(?:abstract\\b|摘\\h*要)\\h*[:：]?\\s*(.{30,7500}?)(?=\\R\\h*(?:key\\h*words?\\b|关键词|引言|introduction\\b|背景介绍)|\\z)");

    Records.Acquired read(byte[] data, String name, String contentType, String sourceUrl,
                          String fetchedUrl, String sourceName, String givenTitle) {
        if (data.length == 0 || data.length > MAX_BYTES) throw bad("文件大小须在 1 字节至 20 MB 之间");
        String fileName = safeName(name);
        String lower = fileName.toLowerCase(Locale.ROOT);
        String mime = trim(contentType).toLowerCase(Locale.ROOT);
        String head = new String(data, 0, Math.min(data.length, 512), StandardCharsets.ISO_8859_1);
        String format;
        String media;
        String title = fileName.replaceFirst("\\.[^.]+$", "");
        String authors = null;
        String publisher = null;
        String published = null;
        String external = null;
        String summary = null;
        String raw = null;
        try {
            if (head.startsWith("%PDF-")) {
                format = "PDF";
                media = "application/pdf";
                if (!lower.endsWith(".pdf")) fileName = safeName(fileName + ".pdf");
                try (var pdf = Loader.loadPDF(data)) {
                    var info = pdf.getDocumentInformation();
                    title = withDefault(info.getTitle(), title);
                    authors = info.getAuthor();
                    summary = abstractFrom(pdfText(pdf, 4));
                } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                    // Keep password-protected originals available for manual review.
                }
            } else if (lower.endsWith(".pdf") || mime.startsWith("application/pdf")) {
                throw bad("该地址或文件没有返回有效的 PDF");
            } else if (lower.endsWith(".docx") || mime.contains("wordprocessingml")) {
                format = "DOCX";
                media = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                try (var document = new XWPFDocument(new ByteArrayInputStream(data));
                     var extractor = new XWPFWordExtractor(document)) {
                    title = withDefault(document.getProperties().getCoreProperties().getTitle(), title);
                    raw = extractor.getText();
                }
            } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || mime.contains("spreadsheetml")
                    || mime.contains("vnd.ms-excel")) {
                format = lower.endsWith(".xls") || mime.contains("vnd.ms-excel") ? "XLS" : "XLSX";
                media = format.equals("XLS") ? "application/vnd.ms-excel"
                        : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                raw = workbookText(data);
            } else if (mime.contains("html") || lower.endsWith(".html") || lower.endsWith(".htm")
                    || head.stripLeading().toLowerCase(Locale.ROOT).matches("(?s)^<(?:!doctype|html|head|body).*")) {
                format = "HTML";
                media = "text/html";
                Document document = html(data, fetchedUrl, contentType);
                title = withDefault(meta(document, "citation_title"), withDefault(document.title(), title));
                authors = String.join("、", document.select("meta[name=citation_author]").eachAttr("content"));
                publisher = withDefault(meta(document, "citation_journal_title"), meta(document, "og:site_name"));
                published = withDefault(meta(document, "citation_publication_date"), meta(document, "article:published_time"));
                external = meta(document, "citation_doi");
                summary = withDefault(meta(document, "description"), meta(document, "og:description"));
                document.select("script, style, nav, footer, header, aside, noscript").remove();
                var article = document.selectFirst("article, main, [role=main]");
                raw = (article == null ? document.body() : article).wholeText();
                if (!lower.endsWith(".html") && !lower.endsWith(".htm")) fileName = safeName(fileName + ".html");
            } else if (lower.endsWith(".txt") || lower.endsWith(".csv") || mime.startsWith("text/plain")
                    || mime.startsWith("text/csv")) {
                format = lower.endsWith(".csv") || mime.startsWith("text/csv") ? "CSV" : "TXT";
                media = format.equals("CSV") ? "text/csv" : "text/plain";
                try {
                    raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString();
                } catch (CharacterCodingException e) {
                    raw = new String(data, Charset.forName("GB18030"));
                }
            } else {
                throw bad("支持 PDF、网页、DOCX、TXT、CSV、XLS 和 XLSX 原件");
            }
        } catch (IOException | IllegalArgumentException e) {
            throw bad("原件无法读取，请核对文件格式");
        }
        if (!"PDF".equals(format) && trim(summary).isBlank()) summary = abstractFrom(raw);
        return new Records.Acquired(limit(withDefault(givenTitle, title), 400), format, fileName, media,
                sourceUrl, fetchedUrl, sourceName, authors, publisher, published, external, limit(summary, 12000),
                limit(trim(raw).replaceAll("(?m)^[\\t ]+$", "").replaceAll("\\n{3,}", "\n\n"), MAX_TEXT), data);
    }

    Document html(byte[] bytes, String url, String contentType) throws IOException {
        String charset = null;
        var match = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([\\w.-]+)").matcher(trim(contentType));
        if (match.find() && Charset.isSupported(match.group(1))) charset = match.group(1);
        return Jsoup.parse(new ByteArrayInputStream(bytes), charset, trim(url));
    }

    String pdfForAnalysis(byte[] data) {
        try (var pdf = Loader.loadPDF(data)) {
            return limit(pdfText(pdf, 40), 42_000);
        } catch (IOException e) {
            return "";
        }
    }

    private String pdfText(org.apache.pdfbox.pdmodel.PDDocument pdf, int pages) throws IOException {
        var stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setEndPage(Math.min(pages, pdf.getNumberOfPages()));
        return stripper.getText(pdf);
    }

    private String workbookText(byte[] data) throws IOException {
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(data));
             var text = new StringWriter()) {
            var formatter = new DataFormatter(Locale.ROOT);
            for (var sheet : workbook) {
                text.append(sheet.getSheetName()).append('\n');
                for (var row : sheet) {
                    for (var cell : row) {
                        text.append(formatter.formatCellValue(cell)).append('\t');
                        if (text.getBuffer().length() >= MAX_TEXT) return text.toString();
                    }
                    text.append('\n');
                }
            }
            return text.toString();
        }
    }

    private String abstractFrom(String input) {
        var matcher = ABSTRACT.matcher(trim(input));
        if (matcher.find()) return limit(matcher.group(1).replaceAll("\\s+", " ").strip(), 4500);
        return null;
    }

    private String meta(Document document, String name) {
        var element = document.selectFirst("meta[name=\"" + name + "\"], meta[property=\"" + name + "\"]");
        return element == null ? "" : element.attr("content");
    }

    static String safeName(String input) {
        String fileName = trim(input).replace('\\', '/');
        fileName = fileName.substring(fileName.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (fileName.length() <= 240) return withDefault(fileName, "original");
        int extension = fileName.lastIndexOf('.');
        String suffix = extension >= fileName.length() - 10 && extension >= 0 ? fileName.substring(extension) : "";
        return limit(fileName, 240 - suffix.length()) + suffix;
    }

    static String nameFrom(URI uri) {
        return uri.getPath().isBlank() || uri.getPath().endsWith("/") ? uri.getHost() : safeName(uri.getPath());
    }

    static String withDefault(String value, String fallback) {
        return trim(value).isBlank() ? fallback : value.strip();
    }
}
