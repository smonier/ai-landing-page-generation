package org.jahia.se.modules.ailandingpagegenerator.ingestion;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for UrlFetchService security controls.
 * No real network calls are made — the service rejects all requests
 * without an allow-list, which is the primary concern here.
 */
public class UrlFetchServiceTest {

    private UrlFetchService service() {
        return new UrlFetchService();
        // Note: without calling updated(), allowedHosts is empty by default → all fetches refused.
    }

    @Test
    public void testFetchRefusedWhenAllowListEmpty() {
        UrlFetchService svc = service();
        String result = svc.fetch("https://example.com/page");
        assertNull("Fetch should be null when allow-list is empty", result);
    }

    @Test
    public void testFetchRefusedForNonHttpsUrl() {
        UrlFetchService svc = service();
        assertNull(svc.fetch("http://example.com/page"));
        assertNull(svc.fetch("ftp://example.com/file"));
        assertNull(svc.fetch("file:///etc/passwd"));
    }

    @Test
    public void testFetchRefusedForNullAndBlankUrl() {
        UrlFetchService svc = service();
        assertNull(svc.fetch(null));
        assertNull(svc.fetch(""));
        assertNull(svc.fetch("   "));
    }

    @Test
    public void testFetchRefusedForInvalidUrl() {
        UrlFetchService svc = service();
        assertNull(svc.fetch("not a url at all"));
        assertNull(svc.fetch("://missing-scheme"));
    }

    @Test
    public void testSsrfInternalAddressBlocked() {
        UrlFetchService svc = service();
        // Even if someone crafts a URL pointing to internal addresses,
        // it must be rejected because no host is on the allow-list.
        assertNull(svc.fetch("https://169.254.169.254/latest/meta-data/"));
        assertNull(svc.fetch("https://localhost/admin"));
        assertNull(svc.fetch("https://127.0.0.1/"));
        assertNull(svc.fetch("https://10.0.0.1/internal"));
    }

    @Test
    public void testHostNotOnAllowListIsRejected() throws Exception {
        UrlFetchService svc = service();
        java.util.Dictionary<String, Object> dict = new java.util.Hashtable<>();
        dict.put("AI_URL_FETCH_ALLOWED_HOSTS", "docs.mycompany.com");
        dict.put("AI_URL_FETCH_MAX_SIZE_BYTES", "5242880");
        dict.put("AI_URL_FETCH_TIMEOUT_MS", "5000");
        svc.updated(dict);

        // A different host must be rejected
        assertNull(svc.fetch("https://evil.com/steal"));
        assertNull(svc.fetch("https://docs.mycompany.com.evil.com/attack"));
    }
}
