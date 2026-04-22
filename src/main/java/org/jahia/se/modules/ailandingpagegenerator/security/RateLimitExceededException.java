package org.jahia.se.modules.ailandingpagegenerator.security;

/**
 * Thrown when a user exceeds their per-minute generation quota.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
