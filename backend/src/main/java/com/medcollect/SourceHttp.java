package com.medcollect;

import static com.medcollect.Support.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.stereotype.Component;

@Component
class SourceHttp {
    private static final Set<String> EUROPE_PMC_HOSTS = Set.of("www.ebi.ac.uk", "europepmc.org", "www.europepmc.org");
    private final Set<String> privateHosts;
    private final CloseableHttpClient http;

    record Download(URI uri, String contentType, String disposition, byte[] bytes) {}
    private record Hop(int status, String location, Download download, String retryAfter) {}

    SourceHttp(AppSettings settings) {
        List<String> allowed = settings.privateSourceHosts() == null ? List.of() : settings.privateSourceHosts();
        privateHosts = allowed.stream().map(host -> host.strip().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        var connections = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(new DnsResolver() {
            @Override
            public InetAddress[] resolve(String host) throws UnknownHostException {
                return addresses(host);
            }

            @Override
            public String resolveCanonicalHostname(String host) {
                return host;
            }
        }).setDefaultConnectionConfig(ConnectionConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(8)).setSocketTimeout(Timeout.ofSeconds(20)).build()).build();
        http = HttpClients.custom()
                .setConnectionManager(connections)
                .disableRedirectHandling().disableAutomaticRetries().disableCookieManagement()
                .setUserAgent("MedCollect/0.1")
                .setDefaultRequestConfig(RequestConfig.custom().setResponseTimeout(Timeout.ofSeconds(20)).build())
                .build();
    }

    Download get(String url) throws IOException {
        URI uri = httpUri(url);
        for (int redirect = 0; redirect <= 4; redirect++) {
            if (uri.toString().length() > 12_000) throw bad("来源请求地址过长");
            // Apache may skip its DNS resolver for literal IPs.
            String host = uri.getHost().replace("[", "").replace("]", "");
            InetAddress[] resolved = addresses(host);
            if (EUROPE_PMC_HOSTS.contains(host.toLowerCase(Locale.ROOT))
                    && java.util.Arrays.stream(resolved).anyMatch(SourceHttp::benchmarkAddress)
                    && (!"https".equalsIgnoreCase(uri.getScheme()) || (uri.getPort() != -1 && uri.getPort() != 443))) {
                throw new IOException("Europe PMC 代理地址只支持标准 HTTPS");
            }
            Hop hop = fetchHop(uri);
            if (hop.download() != null) return hop.download();
            if (hop.location() == null) throw new IOException("来源返回 HTTP " + hop.status());
            uri = httpUri(uri.resolve(hop.location()).toString());
        }
        throw new IOException("来源重定向次数过多");
    }

    private Hop fetchHop(URI uri) throws IOException {
        for (int attempt = 0; ; attempt++) {
            Hop hop;
            try {
                hop = request(uri);
            } catch (SocketTimeoutException | org.apache.hc.core5.http.NoHttpResponseException transientFailure) {
                if (attempt > 0) throw transientFailure;
                pause(500);
                continue;
            }
            if (attempt > 0 || !Set.of(408, 429, 502, 503, 504).contains(hop.status())) return hop;
            long delay = retryDelay(hop.retryAfter());
            if (delay < 0 || delay > 2000) return hop;
            pause(delay);
        }
    }

    static long retryDelay(String header) {
        if (header == null || header.isBlank()) return 500;
        try {
            if (header.matches("[0-9]+")) return Math.multiplyExact(Long.parseLong(header), 1000);
            Instant date = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            return Math.max(0, Duration.between(Instant.now(), date).toMillis());
        } catch (RuntimeException invalid) {
            return -1;
        }
    }

    private void pause(long millis) throws IOException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("采集请求已中断", e);
        }
    }

    private Hop request(URI uri) throws IOException {
        HttpGet get = new HttpGet(uri);
        get.setHeader("Accept-Language", "zh-CN,zh-TW;q=0.9,en;q=0.7");
        return http.execute(get, response -> {
            int status = response.getCode();
            if (status >= 300 && status < 400 && response.getFirstHeader("Location") != null) {
                return new Hop(status, response.getFirstHeader("Location").getValue(), null, null);
            }
            if (status < 200 || status >= 300 || response.getEntity() == null) {
                var retry = response.getFirstHeader("Retry-After");
                return new Hop(status, null, null, retry == null ? null : retry.getValue());
            }
            var entity = response.getEntity();
            if (entity.getContentLength() > DocumentReader.MAX_BYTES) throw new IOException("原件超过 20 MB");
            var header = response.getFirstHeader("Content-Disposition");
            try (var stream = entity.getContent()) {
                byte[] bytes = stream.readNBytes(DocumentReader.MAX_BYTES + 1);
                if (bytes.length > DocumentReader.MAX_BYTES) throw new IOException("原件超过 20 MB");
                return new Hop(status, null, new Download(uri, entity.getContentType(),
                        header == null ? "" : header.getValue(), bytes), null);
            }
        });
    }

    private InetAddress[] addresses(String host) throws UnknownHostException {
        InetAddress[] found = InetAddress.getAllByName(host);
        String normalized = host.toLowerCase(Locale.ROOT);
        if (privateHosts.contains(normalized)) return found;
        for (InetAddress address : found) {
            byte[] bytes = address.getAddress();
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean reserved = bytes.length == 4
                    ? first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127)
                      || (first == 192 && second == 0)
                      || (benchmarkAddress(address) && !EUROPE_PMC_HOSTS.contains(normalized))
                    : (first & 0xe0) != 0x20;
            if (reserved || address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress()) {
                throw new UnknownHostException("来源使用私有或保留地址，需要在服务端登记允许的主机");
            }
        }
        return found;
    }

    private static boolean benchmarkAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        // Some network gateways return a synthetic address for the known public literature service.
        return bytes.length == 4 && Byte.toUnsignedInt(bytes[0]) == 198
                && (Byte.toUnsignedInt(bytes[1]) == 18 || Byte.toUnsignedInt(bytes[1]) == 19);
    }

    @PreDestroy
    void close() throws IOException { http.close(); }
}
