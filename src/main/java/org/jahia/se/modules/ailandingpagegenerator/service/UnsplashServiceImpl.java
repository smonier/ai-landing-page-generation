package org.jahia.se.modules.ailandingpagegenerator.service;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Dictionary;

/**
 * OSGi service implementation of {@link UnsplashService}.
 *
 * Configuration key (in {@code org.jahia.se.modules.ailandingpagegenerator.cfg}):
 *   UNSPLASH_ACCESS_KEY — Unsplash API access key (obtain at https://unsplash.com/developers)
 *
 * The service is optional: if the key is absent every call to {@link #fetchImage}
 * returns {@code null} and the materialization proceeds without images.
 */
@Component(
        service = {UnsplashService.class, ManagedService.class},
        property = {"service.pid=org.jahia.se.modules.ailandingpagegenerator"},
        immediate = true
)
public class UnsplashServiceImpl implements UnsplashService, ManagedService {

    private static final Logger log = LoggerFactory.getLogger(UnsplashServiceImpl.class);

    private static final String SEARCH_BASE  = "https://api.unsplash.com/search/photos";
    private static final int    TIMEOUT_SEC  = 20;
    private static final int    MAX_IMG_MB   = 15;     // refuse images larger than 15 MB

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(TIMEOUT_SEC))
            .build();

    /** Unsplash API access key — null means service is disabled. */
    private volatile String accessKey;

    @Activate
    public void activate() {
        log.info("UnsplashService activated.");
    }

    // ── ManagedService ────────────────────────────────────────────────────────

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) return;
        String key = getStr(dictionary, "UNSPLASH_ACCESS_KEY", null);
        if (key == null || key.isBlank()) {
            accessKey = null;
            log.warn("UnsplashService: UNSPLASH_ACCESS_KEY not configured — image fetching disabled.");
        } else {
            accessKey = key;
            log.info("UnsplashService configured.");
        }
    }

    // ── UnsplashService ───────────────────────────────────────────────────────

    @Override
    public ImageData fetchImage(String query) {
        if (accessKey == null || query == null || query.isBlank()) {
            return null;
        }

        try {
            // ── 1. Search Unsplash ────────────────────────────────────────────
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            String searchUri = SEARCH_BASE + "?query=" + encoded
                    + "&per_page=1&orientation=landscape";

            HttpRequest searchReq = HttpRequest.newBuilder(URI.create(searchUri))
                    .GET()
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .header("Authorization", "Client-ID " + accessKey)
                    .header("Accept-Version", "v1")
                    .build();

            HttpResponse<String> searchResp =
                    http.send(searchReq, HttpResponse.BodyHandlers.ofString());

            if (searchResp.statusCode() != 200) {
                log.warn("UnsplashService: search returned HTTP {} for query '{}'.",
                        searchResp.statusCode(), query);
                return null;
            }

            JSONObject body    = new JSONObject(searchResp.body());
            JSONArray  results = body.optJSONArray("results");
            if (results == null || results.isEmpty()) {
                log.warn("UnsplashService: no results for query '{}'.", query);
                return null;
            }

            JSONObject photo        = results.getJSONObject(0);
            String     photoId      = photo.getString("id");
            String     imageUrl     = photo.getJSONObject("urls").getString("regular");
            String     photographer = "Unsplash";
            if (photo.has("user")) {
                photographer = photo.getJSONObject("user").optString("name", "Unsplash");
            }

            // ── 2. Trigger download tracking (required by Unsplash API guidelines) ──
            triggerDownload(photo);

            // ── 3. Download the image binary ──────────────────────────────────
            HttpRequest imgReq = HttpRequest.newBuilder(URI.create(imageUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(TIMEOUT_SEC))
                    .build();

            HttpResponse<byte[]> imgResp =
                    http.send(imgReq, HttpResponse.BodyHandlers.ofByteArray());

            if (imgResp.statusCode() != 200) {
                log.warn("UnsplashService: image download returned HTTP {}.", imgResp.statusCode());
                return null;
            }

            byte[] bytes = imgResp.body();
            if (bytes.length > MAX_IMG_MB * 1024 * 1024) {
                log.warn("UnsplashService: image exceeds {} MB — skipping.", MAX_IMG_MB);
                return null;
            }

            String mimeType = imgResp.headers()
                    .firstValue("Content-Type")
                    .map(ct -> ct.split(";")[0].trim())
                    .orElse("image/jpeg");

            String fileName = "unsplash-" + photoId + toExtension(mimeType);
            log.info("UnsplashService: fetched '{}' ({} bytes) for query '{}', photo by {}.",
                    fileName, bytes.length, query, photographer);

            return new ImageData(bytes, mimeType, fileName, photographer);

        } catch (Exception e) {
            log.warn("UnsplashService.fetchImage failed for query '{}': {}", query, e.getMessage());
            return null;
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * Fires the Unsplash download-tracking request (best-effort, non-blocking errors).
     * Required by the Unsplash API guidelines.
     */
    private void triggerDownload(JSONObject photo) {
        try {
            JSONObject links = photo.optJSONObject("links");
            if (links == null) return;
            String downloadLocation = links.optString("download_location", "");
            if (downloadLocation.isBlank()) return;

            String trackUrl = downloadLocation
                    + (downloadLocation.contains("?") ? "&" : "?")
                    + "client_id=" + accessKey;

            HttpRequest trackReq = HttpRequest.newBuilder(URI.create(trackUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(5))
                    .header("Authorization", "Client-ID " + accessKey)
                    .header("Accept-Version", "v1")
                    .build();

            http.send(trackReq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("UnsplashService: download-tracking call failed: {}", e.getMessage());
        }
    }

    private static String toExtension(String mimeType) {
        return switch (mimeType) {
            case "image/jpeg" -> ".jpg";
            case "image/png"  -> ".png";
            case "image/webp" -> ".webp";
            case "image/gif"  -> ".gif";
            default           -> ".jpg";
        };
    }

    private static String getStr(Dictionary<String, ?> d, String key, String def) {
        Object v = d.get(key);
        return (v instanceof String s && !s.isBlank()) ? s : def;
    }
}
