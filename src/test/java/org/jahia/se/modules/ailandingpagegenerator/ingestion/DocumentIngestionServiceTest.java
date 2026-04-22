package org.jahia.se.modules.ailandingpagegenerator.ingestion;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.*;

public class DocumentIngestionServiceTest {

    private final DocumentIngestionService service = new DocumentIngestionService();

    @Test
    public void testNullAndBlankInputReturnNull() {
        assertNull(service.extractText(null, null));
        assertNull(service.extractText("", "text/plain"));
        assertNull(service.extractText("   ", "text/plain"));
    }

    @Test
    public void testPlainTextExtractionFromBase64() {
        String text = "Hello, this is a plain text document for testing.";
        String base64 = Base64.getEncoder().encodeToString(text.getBytes());
        String result = service.extractText(base64, "text/plain");
        assertNotNull(result);
        assertTrue(result.contains("Hello"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidBase64Throws() {
        service.extractText("not-valid-base64!!!", "text/plain");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testOversizedPayloadThrows() {
        // 11 MB > 10 MB limit
        byte[] big = new byte[11 * 1024 * 1024];
        String base64 = Base64.getEncoder().encodeToString(big);
        service.extractText(base64, "text/plain");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnsupportedMimeTypeThrows() {
        String base64 = Base64.getEncoder().encodeToString("data".getBytes());
        service.extractText(base64, "application/x-executable");
    }

    @Test
    public void testNullMimeTypeIsAccepted() {
        // Tika will auto-detect; should not throw
        String text = "Sample content";
        String base64 = Base64.getEncoder().encodeToString(text.getBytes());
        // Should not throw — may return text or null depending on Tika detection
        try {
            service.extractText(base64, null);
        } catch (IllegalArgumentException e) {
            fail("Should not throw for null MIME type: " + e.getMessage());
        }
    }
}
