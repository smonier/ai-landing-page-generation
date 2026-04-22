package org.jahia.se.modules.ailandingpagegenerator.ingestion;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Extracts plain text from uploaded documents (TXT, MD, PDF, DOCX).
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

        if (isPdfMimeType(mimeType)) {
            return extractPdfText(bytes);
        }

        if (isDocxMimeType(mimeType)) {
            return extractDocxText(bytes);
        }

        log.warn("Unsupported document format ({}) — skipping text extraction.", mimeType);
        return null;
    }

    private String extractPdfText(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            log.info("Extracted {} characters from PDF document ({} pages).", text.length(), doc.getNumberOfPages());
            return text;
        } catch (Exception e) {
            log.warn("PDF text extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private String extractDocxText(byte[] bytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            String text = extractor.getText();
            log.info("Extracted {} characters from DOCX document.", text.length());
            return text;
        } catch (Exception e) {
            log.warn("DOCX text extraction failed: {}", e.getMessage());
            return null;
        }
    }

    private boolean isTextMimeType(String mimeType) {
        if (mimeType == null) return false;
        String m = mimeType.toLowerCase();
        return m.contains("text") || m.contains("markdown") || m.contains("plain");
    }

    private boolean isPdfMimeType(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().contains("pdf");
    }

    private boolean isDocxMimeType(String mimeType) {
        return mimeType != null && mimeType.toLowerCase().contains("openxmlformats");
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
