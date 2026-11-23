package com.medcollect;

import static com.medcollect.Support.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.crypto.Cipher;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriUtils;

@Component
class CdssClient {
    private static final String PUBLIC_KEY = "MFwwDQYJKoZIhvcNAQEBBQADSwAwSAJBAJrd1c438F5eQekhhKyPuoK4MuTYfwr87bob4AtYG3g8oYC2dfXsE/P1exP0hK1C8vemWjzx6oSDZU6xCbzPmy0CAwEAAQ==";
    private final AppSettings settings;
    private final ObjectMapper json;
    private final Clock clock;
    private final HttpClient http;
    private String accessToken;
    private long tokenExpiry;

    record SearchPage(JsonNode rows, long total) {}

    static class RemoteFailure extends RuntimeException {
        RemoteFailure(String message) { super(message); }
    }

    static final class Rejected extends RemoteFailure {
        Rejected(String message) { super(message); }
    }

    CdssClient(AppSettings settings, ObjectMapper json, Clock clock) throws java.security.GeneralSecurityException {
        this.settings = settings;
        this.json = json;
        this.clock = clock;
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER);
        String pin = trim(settings.cdssCertSha256()).replace(":", "").toLowerCase(Locale.ROOT);
        if (!pin.isBlank()) {
            if (!pin.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid CDSS certificate fingerprint");
            var tls = SSLContext.getInstance("TLS");
            tls.init(null, new X509ExtendedTrustManager[] { new PinnedCertificate(pin) }, null);
            client.sslContext(tls);
        }
        this.http = client.build();
    }

    boolean configured() {
        return !trim(settings.cdssUser()).isBlank() && !trim(settings.cdssPassword()).isBlank();
    }

    boolean writeEnabled() { return configured() && settings.cdssSyncEnabled(); }

    JsonNode templates() {
        String content = data(form("/parameter/find-by-name", Map.of("name", "knowledge-content-template")))
                .path("parameterValue").asText();
        try {
            JsonNode result = json.readTree(content);
            if (result != null && result.isObject()) return result;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
            // Report a template contract mismatch to the operator.
        }
        throw new RemoteFailure("4.23 未返回有效知识模板");
    }

    // Callers choose the read endpoint in server code. Clients only supply search terms or business keys.
    JsonNode reference(String endpoint, Map<String, String> parameters) {
        JsonNode envelope = form(endpoint, parameters);
        data(envelope);
        return envelope;
    }

    JsonNode classes(String root) {
        return data(form("/knowledge/get-class-tree", Map.of("classType", "15", "upperClass", root)));
    }

    String create(Map<String, String> fields) {
        JsonNode saved = saveKnowledge(fields);
        String id = saved.path("id").asText();
        if (id.isBlank() || id.length() > 80) throw new RemoteFailure("4.23 未返回知识主键");
        return id;
    }

    void update(String id, Map<String, String> fields) {
        var payload = new LinkedHashMap<>(fields);
        payload.put("id", id);
        if (!id.equals(saveKnowledge(payload).path("id").asText())) {
            throw new RemoteFailure("4.23 未确认更新原业务主键");
        }
    }

    private JsonNode saveKnowledge(Map<String, String> fields) {
        return data(form("/knowledge/save", fields));
    }

    SearchPage search(Map<String, String> filters, int page, int rows) {
        var fields = new LinkedHashMap<>(filters);
        fields.put("validFlag", "1");
        fields.put("page", Integer.toString(page));
        fields.put("rows", Integer.toString(rows));
        JsonNode response = form("/knowledge/query", fields);
        JsonNode data = data(response);
        if (!data.isArray()) throw new RemoteFailure("4.23 返回的知识查询结果不可识别");
        return new SearchPage(data, response.path("total").asLong(-1));
    }

    JsonNode knowledge(String id) {
        JsonNode item = data(form("/knowledge/find-by-id", Map.of("id", id)));
        if (!id.equals(item.path("id").asText())) throw new RemoteFailure("4.23 未返回指定的知识详情");
        return item;
    }

    String findCreated(String type, String childType, String title, String marker) {
        for (int page = 1; page <= 20; page++) {
            var form = new LinkedHashMap<>(Map.of("type", type, "name", title, "validFlag", "1",
                    "page", Integer.toString(page), "rows", "50"));
            if (childType != null) form.put("childType", childType);
            JsonNode response = form("/knowledge/query", form);
            JsonNode rows = data(response);
            if (!rows.isArray()) throw new RemoteFailure("4.23 返回的知识查询结果不可识别");
            for (JsonNode item : rows) {
                if (item.path("remark").asText().contains(marker) && !item.path("id").asText().isBlank()) {
                    return item.path("id").asText();
                }
            }
            if (rows.size() < 50 || response.path("total").asLong(Long.MAX_VALUE) <= page * 50L) return null;
        }
        throw new RemoteFailure("同名候选过多，请在 4.23 核对同步标识");
    }

    String uploadPdf(String draftId, byte[] bytes) {
        String boundary = "medcollect-" + id();
        var body = new ByteArrayOutputStream(bytes.length + 600);
        String asciiName = "mc-" + java.util.UUID.fromString(draftId) + "-" + sha(bytes) + ".pdf";
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"knowledgeClassName\""
                + "\r\n\r\nMedCollect\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + asciiName
                + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(bytes);
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JsonNode result = data(send("/upload?category=knowledge-affix", "multipart/form-data; boundary=" + boundary,
                body.toByteArray(), token()));
        String url = result.path("url").asText();
        if (url.length() > 1000 || !url.matches("/?[A-Za-z0-9_./%+-]+") || url.contains("..")) {
            throw new RemoteFailure("4.23 未返回可绑定的附件路径");
        }
        return url;
    }

    boolean hasAttachment(String knowledgeId, String url) {
        JsonNode response = attachments(knowledgeId);
        for (JsonNode row : response) {
            if (row.path("validFlag").asInt(1) != 1) continue;
            if (url.equals(row.path("knowledgeAffix").asText())) return true;
            if (url.equals(row.path("url").asText())) return true;
        }
        return false;
    }

    JsonNode attachments(String knowledgeId) {
        var result = json.createArrayNode();
        var seen = new HashSet<String>();
        for (int page = 1; ; page++) {
            var response = form("/knowledge/query-affix", Map.of("knowledgeId",knowledgeId,"page",Integer.toString(page),"rows","100"));
            var rows = data(response);
            if (!rows.isArray()) throw new RemoteFailure("4.23 返回的附件列表不可识别");
            long total = response.path("total").asLong(-1);
            int previous = result.size();
            for (var row : rows) {
                String key = row.path("id").asText("") + "/" + row.path("knowledgeAffix").asText(row.path("url").asText(""));
                if (seen.add(key)) result.add(row);
            }
            if (total >= 0 ? result.size() >= total : rows.size() < 100) return result;
            if (previous == result.size()) throw new RemoteFailure("4.23 附件列表未返回完整分页，暂时无法核对全部原件");
        }
    }

    String pdfChecksum(String path) {
        try {
            byte[] bytes = readAttachment(path);
            if (bytes.length < 5 || !"%PDF-".equals(new String(bytes,0,5,StandardCharsets.US_ASCII))) return null;
            return sha(bytes);
        } catch (IOException | RemoteFailure | org.springframework.web.server.ResponseStatusException e) {
            return null;
        }
    }

    String attachmentUrl(String path) {
        String name = trim(path).replaceFirst("^/", "");
        var encoded = new ArrayList<String>();
        for (String segment : name.split("/",-1)) {
            String decoded;
            try { decoded = UriUtils.decode(segment,StandardCharsets.UTF_8); }
            catch (IllegalArgumentException ex) { throw bad("业务附件路径无效"); }
            if (decoded.isBlank() || decoded.equals(".") || decoded.equals("..") || decoded.contains("/")
                    || decoded.contains("\\") || decoded.contains("%") || decoded.chars().anyMatch(Character::isISOControl)) {
                throw bad("业务附件路径无效");
            }
            encoded.add(UriUtils.encodePathSegment(decoded, StandardCharsets.UTF_8));
        }
        return baseUrl(settings.cdssUrl()) + "/api/upload/view/" + String.join("/",encoded) + "?preview=1";
    }

    byte[] readAttachment(String path) throws IOException {
        var request=HttpRequest.newBuilder(URI.create(attachmentUrl(path))).timeout(Duration.ofSeconds(30))
                .header("Authorization","Bearer "+token()).GET().build();
        try {
            var response=http.send(request,HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()!=200) throw new RemoteFailure("业务附件暂时不可读取，请核对 4.23 原件");
                byte[] bytes=stream.readNBytes(DocumentReader.MAX_BYTES+1);
                if(bytes.length==0 || bytes.length>DocumentReader.MAX_BYTES) throw bad("原件为空或超过 20 MB");
                return bytes;
            }
        } catch(InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new RemoteFailure("读取 4.23 原件已中断");
        }
    }

    void bindAttachment(String knowledgeId, String url, String originalName, String attachmentId) {
        data(form("/knowledge/save-affix", Map.of("knowledgeId", knowledgeId, "knowledgeAffix", url,
                "id", attachmentId, "affixName", limit(originalName, 160), "validFlag", "1")));
    }

    void prepareWrite() { token(); }

    String previewUrl(String id) {
        return baseUrl(settings.cdssUrl()) + "/knowledgePreview?id=" + encode(id);
    }

    JsonNode verifyConnection() { return data(form("/knowledge/get-statistics-by-status", Map.of())); }

    private JsonNode form(String path, Map<String, String> form) {
        return send(path, "application/x-www-form-urlencoded", formBytes(form), token());
    }

    private byte[] formBytes(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(java.util.stream.Collectors.joining("&")).getBytes(StandardCharsets.UTF_8);
    }

    private synchronized String token() {
        if (!configured()) throw new RemoteFailure("尚未配置 4.23 业务账户");
        if (accessToken != null && clock.millis() < tokenExpiry) return accessToken;
        JsonNode response = data(send("/login", "application/x-www-form-urlencoded",
                formBytes(Map.of("username", settings.cdssUser().strip().toLowerCase(Locale.ROOT),
                        "password", encryptPassword(), "captcha", "")), null));
        String token = response.path("access_token").asText();
        if (token.isBlank()) throw new RemoteFailure("4.23 登录未返回访问令牌");
        accessToken = token;
        tokenExpiry = clock.millis() + Math.max(1, response.path("expires_in").asLong(600) - 30) * 1000;
        return token;
    }

    private String encryptPassword() {
        try {
            var key = KeyFactory.getInstance("RSA").generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            var cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(cipher.doFinal(settings.cdssPassword().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new RemoteFailure("无法编码 4.23 登录凭据");
        }
    }

    private JsonNode send(String path, String contentType, byte[] body, String token) {
        var request = HttpRequest.newBuilder(URI.create(baseUrl(settings.cdssUrl()) + "/api" + path))
                .timeout(Duration.ofSeconds(40)).header("Content-Type", contentType)
                .header("Accept", "application/json").header("X-Requested-With", "XMLHttpRequest");
        if (token != null) request.header("Authorization", "Bearer " + token);
        try {
            var response = http.send(request.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                synchronized (this) {
                    accessToken = null;
                    tokenExpiry = 0;
                }
                throw new Rejected("4.23 拒绝当前访问，请检查账户权限或重试");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RemoteFailure("4.23 返回 HTTP " + response.statusCode());
            }
            return json.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteFailure("4.23 请求已中断");
        } catch (IOException e) {
            throw new RemoteFailure("无法读取 4.23 响应，请检查网络和证书配置");
        }
    }

    private JsonNode data(JsonNode response) {
        if (response == null || !response.isObject() || !response.has("code")) {
            throw new RemoteFailure("4.23 返回格式不符合知识管理接口");
        }
        if (response.path("code").asInt(-1) != 0 || !response.path("success").asBoolean(true)) {
            throw new Rejected(limit(DocumentReader.withDefault(response.path("msg").asText(),
                    "4.23 拒绝业务请求"), 300));
        }
        return response.path("data");
    }

    private static final class PinnedCertificate extends X509ExtendedTrustManager {
        private final String fingerprint;

        PinnedCertificate(String fingerprint) { this.fingerprint = fingerprint; }

        private void check(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0 || !sha(chain[0].getEncoded()).equals(fingerprint)) {
                throw new CertificateException("CDSS certificate fingerprint mismatch");
            }
            chain[0].checkValidity();
        }

        @Override public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException { check(chain); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException(); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, Socket socket) throws CertificateException { throw new CertificateException(); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String auth, SSLEngine engine) throws CertificateException { throw new CertificateException(); }
    }
}
