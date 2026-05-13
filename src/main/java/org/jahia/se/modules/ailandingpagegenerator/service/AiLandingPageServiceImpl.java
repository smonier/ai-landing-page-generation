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
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.cm.ConfigurationException;
import org.osgi.service.cm.ManagedService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
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

    // ── Anthropic config ──────────────────────────────────────────────────────
    private volatile String apiKey;
    private volatile String apiBaseUrl      = "https://api.anthropic.com";
    private volatile String model           = "claude-sonnet-4-6";

    // ── OpenAI config ─────────────────────────────────────────────────────────
    private volatile String openaiApiKey;
    private volatile String openaiBaseUrl   = "https://api.openai.com";
    private volatile String openaiModel     = "gpt-4o";

    // ── DeepSeek config (OpenAI-compatible API) ─────────────────────────────
    private volatile String deepseekApiKey;
    private volatile String deepseekBaseUrl = "https://api.deepseek.com";
    private volatile String deepseekModel   = "deepseek-chat";

    // ── Shared config ─────────────────────────────────────────────────────────
    private volatile int    maxInputTokens  = 100_000;
    private volatile int    maxOutputTokens = 8_000;
    private volatile long   timeoutMs       = 60_000L;
    private volatile String audiences       = "IT,Finance,Marketing,Sales,HR,C-Suite,General Public,Students,Developers";
    private volatile String tones           = "Professional,Friendly,Bold,Playful,Authoritative,Witty,Inspirational,Concise,Verbose";

    // ── HTTP client (for OpenAI) ──────────────────────────────────────────────
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
        // Anthropic
        apiKey          = getString(dictionary, "AI_API_KEY", null);
        apiBaseUrl      = getString(dictionary, "AI_API_BASE_URL", "https://api.anthropic.com");
        model           = getString(dictionary, "AI_MODEL", "claude-sonnet-4-6");
        // OpenAI
        openaiApiKey    = getString(dictionary, "OPENAI_API_KEY", null);
        openaiBaseUrl   = getString(dictionary, "OPENAI_API_BASE_URL", "https://api.openai.com");
        openaiModel     = getString(dictionary, "OPENAI_MODEL", "gpt-4o");
        // DeepSeek
        deepseekApiKey  = getString(dictionary, "DEEPSEEK_API_KEY", null);
        deepseekBaseUrl = getString(dictionary, "DEEPSEEK_API_BASE_URL", "https://api.deepseek.com");
        deepseekModel   = getString(dictionary, "DEEPSEEK_MODEL", "deepseek-chat");
        // Shared
        maxInputTokens  = getInt(dictionary, "AI_MAX_INPUT_TOKENS", 100_000);
        maxOutputTokens = getInt(dictionary, "AI_MAX_OUTPUT_TOKENS", 8_000);
        timeoutMs       = getLong(dictionary, "AI_TIMEOUT_MS", 60_000L);
        audiences       = getString(dictionary, "AI_AUDIENCES", "IT,Finance,Marketing,Sales,HR");
        tones           = getString(dictionary, "AI_TONES", "Professional,Friendly,Bold,Playful,Authoritative");

        List<String> providers = getAvailableProviders();
        if (providers.isEmpty()) {
            log.error("No AI provider configured — set AI_API_KEY (Anthropic), OPENAI_API_KEY (OpenAI), or DEEPSEEK_API_KEY (DeepSeek).");
        } else {
            log.info("AI configuration updated. Available providers: {} anthropic-model={} openai-model={} deepseek-model={}",
                    providers, model, openaiModel, deepseekModel);
        }
    }

    // ── AiLandingPageService ──────────────────────────────────────────────────
    @Override
    public String generatePageStructure(
            String provider,
            String prompt,
            String audience,
            String tone,
            String documentBase64,
            String documentMimeType,
            List<String> urls) {

        // ── Resolve provider ─────────────────────────────────────────────────
        String resolvedProvider;
        if (provider == null || provider.isBlank()) {
            // Default to the first configured provider
            List<String> available = getAvailableProviders();
            if (available.isEmpty()) {
                throw new IllegalStateException("No AI provider configured. Set AI_API_KEY, OPENAI_API_KEY, or DEEPSEEK_API_KEY.");
            }
            resolvedProvider = available.get(0);
        } else {
            resolvedProvider = provider.trim().toLowerCase();
        }

        if (!"anthropic".equals(resolvedProvider)
                && !"openai".equals(resolvedProvider)
                && !"deepseek".equals(resolvedProvider)) {
            throw new IllegalArgumentException("Unsupported provider: " + resolvedProvider);
        }

        switch (resolvedProvider) {
            case "openai":
                if (openaiApiKey == null || openaiApiKey.isBlank()) {
                    throw new IllegalStateException("OPENAI_API_KEY is not configured.");
                }
                break;
            case "deepseek":
                if (deepseekApiKey == null || deepseekApiKey.isBlank()) {
                    throw new IllegalStateException("DEEPSEEK_API_KEY is not configured.");
                }
                break;
            case "anthropic":
            default:
                if (apiKey == null || apiKey.isBlank()) {
                    throw new IllegalStateException("AI_API_KEY (Anthropic) is not configured.");
                }
                break;
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
            // Ingest optional document (supports TXT, MD, PDF, DOCX)
            String documentContext = null;
            if (documentBase64 != null && !documentBase64.isBlank()) {
                documentContext = documentIngestionService.extractText(documentBase64, documentMimeType);
                if (documentContext != null) {
                    log.info("Document context extracted: {} chars from {}.", documentContext.length(), documentMimeType);
                } else {
                    log.warn("Could not extract text from document type '{}' — document will be ignored.", documentMimeType);
                }
            }

            // Fetch optional URLs (server-side only)
            StringBuilder urlContext = new StringBuilder();
            if (urls != null) {
                for (String url : urls) {
                    String fetched = urlFetchService.fetch(url);
                    if (fetched != null) {
                        log.info("Fetched {} chars from URL: {}", fetched.length(), url);
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

            String rawResult;
            switch (resolvedProvider) {
                case "openai":
                    rawResult = callChatCompletions(openaiBaseUrl, openaiApiKey, openaiModel, assembled, "OpenAI");
                    break;
                case "deepseek":
                    rawResult = callChatCompletions(deepseekBaseUrl, deepseekApiKey, deepseekModel, assembled, "DeepSeek");
                    break;
                case "anthropic":
                default:
                    rawResult = callAnthropic(assembled);
                    break;
            }

            // Token tracking best-effort (Anthropic provides exact counts; OpenAI counted inside callOpenAI)
            success = true;
            return stripCodeFences(rawResult);

        } catch (Exception e) {
            errorClass = e.getClass().getSimpleName();
            throw new RuntimeException("AI generation failed: " + e.getMessage(), e);
        } finally {
            long duration = System.currentTimeMillis() - startMs;
            String usedModel;
            switch (resolvedProvider) {
                case "openai":
                    usedModel = openaiModel;
                    break;
                case "deepseek":
                    usedModel = deepseekModel;
                    break;
                case "anthropic":
                default:
                    usedModel = model;
                    break;
            }
            RequestLogger.log(currentUser(), duration, usedModel, inputTokens, outputTokens,
                    success, errorClass, promptHash);
        }
    }

    /** Invoke Anthropic Claude. */
    private String callAnthropic(PromptAssembler.AssembledPrompt assembled) {
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

        StringBuilder result = new StringBuilder();
        for (ContentBlock block : message.content()) {
            Optional<com.anthropic.models.messages.TextBlock> tb = block.text();
            tb.ifPresent(t -> result.append(t.text()));
        }
        return result.toString();
    }

        /** Invoke OpenAI-compatible Chat Completions APIs (OpenAI, DeepSeek). */
        private String callChatCompletions(
            String baseUrl,
            String apiKey,
            String providerModel,
            PromptAssembler.AssembledPrompt assembled,
            String providerName) throws Exception {
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", assembled.systemPrompt()));
        messages.put(new JSONObject().put("role", "user").put("content", assembled.userMessage()));

        JSONObject body = new JSONObject()
            .put("model", providerModel)
                .put("max_tokens", maxOutputTokens)
                .put("messages", messages);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(providerName + " API error HTTP " + response.statusCode() + ": " + response.body());
        }

        return new JSONObject(response.body())
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
    }

    @Override
    public String getAudiences() { return audiences; }

    @Override
    public String getTones() { return tones; }

    @Override
    public List<String> getAvailableProviders() {
        List<String> providers = new ArrayList<>();
        if (apiKey != null && !apiKey.isBlank()) {
            providers.add("anthropic");
        }
        if (openaiApiKey != null && !openaiApiKey.isBlank()) {
            providers.add("openai");
        }
        if (deepseekApiKey != null && !deepseekApiKey.isBlank()) {
            providers.add("deepseek");
        }
        return Collections.unmodifiableList(providers);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Strips markdown code fences from the AI response.
     * Handles ```json ... ```, ``` ... ```, and any leading/trailing whitespace.
     */
    private static String stripCodeFences(String raw) {
        if (raw == null) return null;
        String trimmed = raw.strip();
        // Match an optional language tag after the opening fence
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline != -1) {
                trimmed = trimmed.substring(firstNewline + 1);
            }

            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.lastIndexOf("```"));
            }

            return trimmed.strip();
        }

        return trimmed;
    }

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
