package org.jahia.se.modules.ailandingpagegenerator.ingestion;

import org.jsoup.Jsoup;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fetches remote URLs server-side for use as page-generation context.
 *
 * Security hardening:
 *  - Host allow-list: only hosts listed in AI_URL_FETCH_ALLOWED_HOSTS are reachable.
 *    If the list is empty, all fetching is refused.
 *  - SSRF prevention: the resolved host is checked against the allow-list AFTER URI parsing;
 *    only HTTPS (or explicitly whitelisted HTTP) is allowed.
 *  - Response size is capped at AI_URL_FETCH_MAX_SIZE_BYTES.
 *  - Connection + read timeout is enforced.
 *  - Extracted text is treated as untrusted by PromptAssembler.
 */
@Component(
        service = {UrlFetchService.class, ManagedService.class},
        property = {"service.pid=org.jahia.se.modules.ailandingpagegenerator"},
        immediate = true
)
public class UrlFetchService implements ManagedService {

    private static final Logger log = LoggerFactory.getLogger(UrlFetchService.class);

    private volatile Set<String> allowedHosts = Collections.emptySet();
    private volatile int maxSizeBytes = 5 * 1024 * 1024;   // 5 MB
    private volatile long timeoutMs   = 10_000L;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) return;
        String hosts = getStr(dictionary, "AI_URL_FETCH_ALLOWED_HOSTS", "");
        if (hosts == null || hosts.isBlank()) {
            allowedHosts = Collections.emptySet();
        } else {
            allowedHosts = Arrays.stream(hosts.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        }
        maxSizeBytes = getInt(dictionary, "AI_URL_FETCH_MAX_SIZE_BYTES", 5 * 1024 * 1024);
        timeoutMs    = getLong(dictionary, "AI_URL_FETCH_TIMEOUT_MS", 10_000L);
        log.debug("UrlFetchService configured. allowedHosts={} maxSize={}", allowedHosts, maxSizeBytes);
    }

    /**
     * Fetch the text content of the given URL, enforcing all security controls.
     *
     * @param url the URL to fetch
     * @return extracted plain text, or null on error / blocked
     */
    public String fetch(String url) {
        if (url == null || url.isBlank()) return null;

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            log.warn("UrlFetchService: invalid URL '{}'.", url);
            return null;
        }

        // ── SSRF guard: enforce HTTPS and host allow-list ─────────────────
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme)) {
            log.warn("UrlFetchService: rejected non-HTTPS URL '{}'.", url);
            return null;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            log.warn("UrlFetchService: URL has no host '{}'.", url);
            return null;
        }

        if (allowedHosts.isEmpty()) {
            log.warn("UrlFetchService: AI_URL_FETCH_ALLOWED_HOSTS is empty — URL fetch disabled.");
            return null;
        }

        if (!allowedHosts.contains(host.toLowerCase())) {
            log.warn("UrlFetchService: host '{}' is not on the allow-list.", host);
            return null;
        }

        // ── Fetch ─────────────────────────────────────────────────────────
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .GET()
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", "Jahia-AiLandingPageGenerator/1.0")
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("UrlFetchService: HTTP {} for '{}'.", response.statusCode(), url);
                return null;
            }

            byte[] body = response.body();
            if (body.length > maxSizeBytes) {
                log.warn("UrlFetchService: response from '{}' exceeds {} bytes — truncating.", url, maxSizeBytes);
                byte[] truncated = new byte[maxSizeBytes];
                System.arraycopy(body, 0, truncated, 0, maxSizeBytes);
                body = truncated;
            }

            // Strip HTML tags
            String raw = new String(body);
            String text = Jsoup.parse(raw).text();
            log.debug("UrlFetchService: fetched {} chars from '{}'.", text.length(), url);
            return text;

        } catch (IOException e) {
            log.error("UrlFetchService: I/O error fetching '{}'.", url, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("UrlFetchService: interrupted while fetching '{}'.", url);
            return null;
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────
    private static String getStr(Dictionary<String, ?> d, String key, String def) {
        Object v = d.get(key);
        return v instanceof String ? (String) v : def;
    }

    private static int getInt(Dictionary<String, ?> d, String key, int def) {
        Object v = d.get(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.toString().trim()); } catch (NumberFormatException e) { return def; }
    }

    private static long getLong(Dictionary<String, ?> d, String key, long def) {
        Object v = d.get(key);
        if (v == null) return def;
        try { return Long.parseLong(v.toString().trim()); } catch (NumberFormatException e) { return def; }
    }
}
