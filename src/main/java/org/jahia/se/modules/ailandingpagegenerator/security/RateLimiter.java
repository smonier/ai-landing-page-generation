package org.jahia.se.modules.ailandingpagegenerator.security;

import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simple per-user, per-minute token-bucket rate limiter.
 * Counters are reset on a rolling 60-second window starting from the first
 * request in that window.
 */
@Component(
        service = {RateLimiter.class, ManagedService.class},
        property = {"service.pid=org.jahia.se.modules.ailandingpagegenerator"},
        immediate = true
)
public class RateLimiter implements ManagedService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    private volatile int limitPerUserPerMinute = 5;

    private record UserBucket(AtomicInteger count, long windowStartMs) {}
    private final Map<String, UserBucket> buckets = new ConcurrentHashMap<>();

    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) return;
        Object v = dictionary.get("AI_RATE_LIMIT_PER_USER_PER_MINUTE");
        if (v != null) {
            try { limitPerUserPerMinute = Integer.parseInt(v.toString().trim()); }
            catch (NumberFormatException e) { /* keep default */ }
        }
    }

    /**
     * Check whether the user is within quota and increment their counter.
     *
     * @param username the authenticated user name
     * @throws RateLimitExceededException if the user has exceeded their per-minute quota
     */
    public void checkAndIncrement(String username) {
        long now = System.currentTimeMillis();
        buckets.compute(username, (u, existing) -> {
            if (existing == null || now - existing.windowStartMs() >= 60_000L) {
                return new UserBucket(new AtomicInteger(1), now);
            }
            int count = existing.count().incrementAndGet();
            if (count > limitPerUserPerMinute) {
                log.warn("Rate limit exceeded for user '{}' ({} req/min).", username, count);
                throw new RateLimitExceededException(
                        "Generation rate limit exceeded. Please wait before generating again.");
            }
            return existing;
        });
    }
}
