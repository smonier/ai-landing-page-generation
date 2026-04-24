package org.jahia.se.modules.ailandingpagegenerator.service;

import java.util.List;

/**
 * Service contract for AI landing page generation.
 * Supports multiple AI providers (Anthropic Claude, OpenAI GPT).
 */
public interface AiLandingPageService {

    /**
     * Generate a landing-page structure from the author's inputs.
     *
     * @param provider        AI provider to use: "anthropic" or "openai".
     *                        If null/empty, defaults to the first configured provider.
     * @param prompt          natural-language description of the desired page
     * @param audience        target audience identifier (e.g. "IT", "Finance")
     * @param tone            desired tone identifier (e.g. "Professional", "Bold")
     * @param documentBase64  optional base-64-encoded document (PDF/DOCX/TXT/MD)
     * @param documentMimeType MIME type of the uploaded document, or null
     * @param urls            optional list of URLs to fetch server-side for context
     * @return JSON string representing the generated page component tree
     */
    String generatePageStructure(
            String provider,
            String prompt,
            String audience,
            String tone,
            String documentBase64,
            String documentMimeType,
            List<String> urls);

    /** Returns the comma-separated list of configured audience values. */
    String getAudiences();

    /** Returns the comma-separated list of configured tone values. */
    String getTones();

    /**
     * Returns which AI providers are currently configured (have a non-empty API key).
     * Possible values in the list: "anthropic", "openai".
     */
    List<String> getAvailableProviders();
}
