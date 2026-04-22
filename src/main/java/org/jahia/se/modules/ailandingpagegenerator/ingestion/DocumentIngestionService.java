package org.jahia.se.modules.ailandingpagegenerator.ingestion;

import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Extracts plain text from uploaded documents (TXT, MD).
 * Binary formats (PDF, DOCX) are accepted but return null — the caller skips
 * document context gracefully. Uses only standard Java — no external library
 * dependency required in the OSGi container.
 */
@Component(service = DocumentIngestionService.class)
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private static final int MAX_BYTES = 10 * 1024 * 1024;

    public String extractText(String base64Content, String mimeType) {
        if (base64Content == null || base64Content.isBlank()) {
            return null;
        }

        validateMimeType(mimeType);

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Content.trim());
        } catch (IllegalArgumentException e) {
            log.warn("Document ingestion: invalid base-64 payload.");
            throw new IllegalArgumentException("Document is not valid base-64.", e);
        }

        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException(
                    "Document exceeds maximum allowed size of 10 MB (got " + bytes.length + " bytes).");
        }

        if (isTextMimeType(mimeType)) {
            String text = new String(bytes, StandardCharsets.UTF_8);
            log.debug("Extracted {} characters from {} document.", text.length(), mimeType);
            return text;
        }

        log.info("Binary document format ({}) received — skipping text extraction.", mimeType);
        return null;
    }

    private boolean isTextMimeType(String mimeType) {
        if (mimeType == null) return false;
        String m = mimeType.toLowerCase();
        return m.contains("text") || m.contains("markdown") || m.contains("plain");
    }

    private void validateMimeType(String mimeType) {
        if (mimeType == null) return;
        String m = mimeType.toLowerCase();
        if (m.contains("pdf") || m.contains("word") || m.contains("text") ||
                m.contains("markdown") || m.contains("plain") || m.contains("openxmlformats")) {
            return;
        }
        throw new IllegalArgumentException("Unsupported document MIME type: " + mimeType);
    }
}
