package org.jahia.se.modules.ailandingpagegenerator.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import org.jahia.se.modules.ailandingpagegenerator.ingestion.DocumentIngestionService;
import org.jahia.se.modules.ailandingpagegenerator.ingestion.UrlFetchService;
import org.jahia.se.modules.ailandingpagegenerator.observability.RequestLogger;
import org.jahia.se.modules.ailandingpagegenerator.prompt.PromptAssembler;
import org.jahia.se.modules.ailandingpagegenerator.security.RateLimiter;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Dictionary;
import java.util.List;
import java.util.Optional;

@Component(
        service = {AiLandingPageService.class, ManagedService.class},
        property = {"service.pid=org.jahia.se.modules.ailandingpagegenerator"},
        immediate = true
)
public class AiLandingPageServiceImpl implements AiLandingPageService, ManagedService {

    private static final Logger log = LoggerFactory.getLogger(AiLandingPageServiceImpl.class);

    // ── config fields ─────────────────────────────────────────────────────────
    private volatile String apiKey;
    private volatile String apiBaseUrl  = "https://api.anthropic.com";
    private volatile String model       = "claude-sonnet-4-6";
    private volatile int    maxInputTokens  = 100_000;
    private volatile int    maxOutputTokens = 8_000;
    private volatile long   timeoutMs   = 60_000L;
    private volatile String audiences   = "IT,Finance,Marketing,Sales,HR,C-Suite,General Public,Students,Developers";
    private volatile String tones       = "Professional,Friendly,Bold,Playful,Authoritative,Witty,Inspirational,Concise,Verbose";

    // ── injected services ─────────────────────────────────────────────────────
    private DocumentIngestionService documentIngestionService;
    private UrlFetchService          urlFetchService;
    private PromptAssembler          promptAssembler;
    private RateLimiter              rateLimiter;

    @Activate
    public void activate() {
        log.info("AiLandingPageService activated. model={} baseUrl={}", model, apiBaseUrl);
    }

    @Reference
    public void setDocumentIngestionService(DocumentIngestionService s) { this.documentIngestionService = s; }

    @Reference
    public void setUrlFetchService(UrlFetchService s) { this.urlFetchService = s; }

    @Reference
    public void setPromptAssembler(PromptAssembler s) { this.promptAssembler = s; }

    @Reference
    public void setRateLimiter(RateLimiter s) { this.rateLimiter = s; }

    // ── ManagedService ────────────────────────────────────────────────────────
    @Override
    public void updated(Dictionary<String, ?> dictionary) throws ConfigurationException {
        if (dictionary == null) return;
        apiKey          = getString(dictionary, "AI_API_KEY", null);
        apiBaseUrl      = getString(dictionary, "AI_API_BASE_URL", "https://api.anthropic.com");
        model           = getString(dictionary, "AI_MODEL", "claude-sonnet-4-6");
        maxInputTokens  = getInt(dictionary, "AI_MAX_INPUT_TOKENS", 100_000);
        maxOutputTokens = getInt(dictionary, "AI_MAX_OUTPUT_TOKENS", 8_000);
        timeoutMs       = getLong(dictionary, "AI_TIMEOUT_MS", 60_000L);
        audiences       = getString(dictionary, "AI_AUDIENCES", "IT,Finance,Marketing,Sales,HR");
        tones           = getString(dictionary, "AI_TONES", "Professional,Friendly,Bold,Playful,Authoritative");

        if (apiKey == null || apiKey.isBlank()) {
            log.error("AI_API_KEY not configured — generation will fail until key is set.");
        } else {
            log.info("AI configuration updated. model={} baseUrl={}", model, apiBaseUrl);
        }
    }

    // ── AiLandingPageService ──────────────────────────────────────────────────
    @Override
    public String generatePageStructure(
            String prompt,
            String audience,
            String tone,
            String documentBase64,
            String documentMimeType,
            List<String> urls) {

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("AI_API_KEY is not configured.");
        }

        // Rate-limit check (throws RateLimitExceededException if over quota)
        rateLimiter.checkAndIncrement(currentUser());

        long startMs = System.currentTimeMillis();
        String promptHash = null;
        int inputTokens = 0;
        int outputTokens = 0;
        boolean success = false;
        String errorClass = null;

        try {
            // Ingest optional document
            String documentContext = null;
            if (documentBase64 != null && !documentBase64.isBlank()) {
                documentContext = documentIngestionService.extractText(documentBase64, documentMimeType);
            }

            // Fetch optional URLs (server-side only)
            StringBuilder urlContext = new StringBuilder();
            if (urls != null) {
                for (String url : urls) {
                    String fetched = urlFetchService.fetch(url);
                    if (fetched != null) {
                        urlContext.append(fetched).append("\n\n");
                    }
                }
            }

            // Assemble final prompt — untrusted content is sandboxed
            PromptAssembler.AssembledPrompt assembled = promptAssembler.assemble(
                    prompt, audience, tone,
                    documentContext,
                    urlContext.length() > 0 ? urlContext.toString() : null,
                    maxInputTokens);

            promptHash = assembled.promptHash();

            // Invoke Claude
            AnthropicClient client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .baseUrl(apiBaseUrl)
                    .build();

            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.of(model))
                    .maxTokens(maxOutputTokens)
                    .system(assembled.systemPrompt())
                    .addUserMessage(assembled.userMessage())
                    .build();

            Message message = client.messages().create(params);

            inputTokens  = message.usage() != null ? (int) message.usage().inputTokens()  : 0;
            outputTokens = message.usage() != null ? (int) message.usage().outputTokens() : 0;

            StringBuilder result = new StringBuilder();
            for (ContentBlock block : message.content()) {
                Optional<com.anthropic.models.messages.TextBlock> tb = block.text();
                tb.ifPresent(t -> result.append(t.text()));
            }

            success = true;
            return result.toString();

        } catch (Exception e) {
            errorClass = e.getClass().getSimpleName();
            throw new RuntimeException("AI generation failed: " + e.getMessage(), e);
        } finally {
            long duration = System.currentTimeMillis() - startMs;
            RequestLogger.log(currentUser(), duration, model, inputTokens, outputTokens,
                    success, errorClass, promptHash);
        }
    }

    @Override
    public String getAudiences() { return audiences; }

    @Override
    public String getTones() { return tones; }

    // ── helpers ───────────────────────────────────────────────────────────────
    private String currentUser() {
        try {
            javax.jcr.Session s = org.jahia.services.content.JCRSessionFactory
                    .getInstance().getCurrentUserSession();
            return s != null ? s.getUserID() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String getString(Dictionary<String, ?> d, String key, String def) {
        Object v = d.get(key);
        return (v instanceof String && !((String) v).isBlank()) ? (String) v : def;
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
