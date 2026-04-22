package org.jahia.se.modules.ailandingpagegenerator.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Structured request logger.
 *
 * Logs per-request observability data with NO content leakage:
 *  - User identifier
 *  - Timestamp (ISO-8601)
 *  - Duration in milliseconds
 *  - Model used
 *  - Input and output token counts
 *  - Success / failure flag
 *  - Error class (if any)
 *  - SHA-256 hash of the combined prompt (not the prompt itself)
 */
public final class RequestLogger {

    private static final Logger log = LoggerFactory.getLogger(RequestLogger.class);

    private RequestLogger() {}

    public static void log(
            String user,
            long durationMs,
            String model,
            int inputTokens,
            int outputTokens,
            boolean success,
            String errorClass,
            String promptHash) {

        log.info("AI_REQUEST user={} ts={} durationMs={} model={} inputTokens={} outputTokens={} success={} errorClass={} promptHash={}",
                user,
                Instant.now(),
                durationMs,
                model,
                inputTokens,
                outputTokens,
                success,
                errorClass != null ? errorClass : "-",
                promptHash != null ? promptHash : "-");
    }
}
