package com.medcollect;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.web.server.ResponseStatusException;

final class Support {
    private Support() {}

    static String id() { return UUID.randomUUID().toString(); }

    static String trim(String value) { return value == null ? "" : value.strip(); }

    static String limit(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max > 0 && Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max);
    }

    static String requireText(String value, String message) {
        String text = trim(value);
        if (text.isEmpty()) throw bad(message);
        return text;
    }

    static ResponseStatusException bad(String message) { return new ResponseStatusException(BAD_REQUEST, message); }
    static ResponseStatusException conflict(String message) { return new ResponseStatusException(CONFLICT, message); }
    static ResponseStatusException missing(String message) { return new ResponseStatusException(NOT_FOUND, message); }

    static String sha(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha(String value) { return sha(value.getBytes(StandardCharsets.UTF_8)); }

    static URI httpUri(String value) {
        try {
            URI uri = URI.create(trim(value));
            if (uri.getHost() != null && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
                String ascii = uri.normalize().toASCIIString();
                int fragment = ascii.indexOf('#');
                return URI.create(fragment < 0 ? ascii : ascii.substring(0, fragment));
            }
        } catch (IllegalArgumentException ignored) {
            // Report invalid operator input with a single message.
        }
        throw bad("请输入合法的 HTTP 或 HTTPS 地址");
    }

    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    static String baseUrl(String value) {
        URI uri = httpUri(value);
        if (uri.getRawQuery() != null) throw bad("服务地址不能包含查询参数");
        return uri.toString().replaceAll("/+$", "");
    }

    static void changed(int count) {
        if (count != 1) throw conflict("数据状态已变化，请刷新后重试");
    }
}
