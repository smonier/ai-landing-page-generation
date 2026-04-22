package org.jahia.se.modules.ailandingpagegenerator.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.se.modules.ailandingpagegenerator.security.RateLimitExceededException;
import org.jahia.se.modules.ailandingpagegenerator.service.AiLandingPageService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Jahia Action that calls the AI service to generate a landing-page JSON structure.
 *
 * Registered as action name: {@code generatePageAction}
 * Required permission: {@code jcr:write_default} (editor role or stronger)
 *
 * Request (POST, multipart/form-data):
 *   prompt        – natural-language description (required, text field)
 *   audience      – selected audience key (required, text field)
 *   tone          – selected tone key (required, text field)
 *   documentFile  – uploaded document file (optional, binary file part: PDF/DOCX/TXT)
 *   urls          – comma-separated list of URLs (optional, text field)
 *
 * Implementation note:
 *   Jahia’s built-in FileUpload filter intercepts multipart requests BEFORE any action runs.
 *   It consumes the request body and stores the result as a request attribute under
 *   the key {@code org.jahia.utils.FileUpload} (class name). The ActionFilter then
 *   populates the {@code parameters} map from the parsed form fields. We read text
 *   fields from that map and retrieve the file bytes via reflection on the attribute.
 *
 * Response JSON:
 *   { "structureJson": "...", "success": true }
 *   { "error": "...", "success": false }
 */
@Component(service = Action.class)
public class GeneratePageAction extends Action {

    private static final Logger log = LoggerFactory.getLogger(GeneratePageAction.class);

    private AiLandingPageService aiService;

    @Activate
    public void activate() {
        setName("generatePageAction");
        setRequireAuthenticatedUser(true);
        setRequiredPermission("jcr:write_default");
        setRequiredWorkspace("default");
        setRequiredMethods("POST");
    }

    @Reference(service = AiLandingPageService.class)
    public void setAiService(AiLandingPageService aiService) {
        this.aiService = aiService;
    }

    @Override
    public ActionResult doExecute(
            HttpServletRequest request,
            RenderContext renderContext,
            Resource resource,
            JCRSessionWrapper session,
            Map<String, List<String>> parameters,
            URLResolver urlResolver) throws Exception {

        JSONObject resp = new JSONObject();

        try {
            // ── Text fields ─────────────────────────────────────────────────────────────
            // Jahia’s ActionFilter populates the parameters map from its own FileUpload
            // filter (for multipart) so form fields are available here directly.
            // request.getParameter() is a fallback for URL-encoded or query-string params.
            String prompt   = coalesce(single(parameters, "prompt"),   request.getParameter("prompt"));
            String audience = coalesce(single(parameters, "audience"), request.getParameter("audience"));
            String tone     = coalesce(single(parameters, "tone"),     request.getParameter("tone"));
            String urlsCsv  = coalesce(single(parameters, "urls"),     request.getParameter("urls"));
            List<String> urls = splitUrls(urlsCsv);

            log.info("GeneratePageAction: prompt='{}...' audience='{}' tone='{}'",
                    prompt != null && prompt.length() > 60 ? prompt.substring(0, 60) : prompt,
                    audience, tone);

            if (prompt   == null || prompt.isBlank())   throw new IllegalArgumentException("Required parameter missing: prompt");
            if (audience == null || audience.isBlank()) throw new IllegalArgumentException("Required parameter missing: audience");
            if (tone     == null || tone.isBlank())     throw new IllegalArgumentException("Required parameter missing: tone");

            // ── Uploaded file ─────────────────────────────────────────────────────────
            // Jahia’s FileUpload filter already consumed the multipart body and stored
            // the result as a request attribute. We use reflection to read it without
            // creating a compile-time dependency on Jahia’s internal FileUpload class.
            String documentBase64 = null;
            String documentMime   = null;

            // Jahia's FileUpload filter (org.jahia.tools.files.FileUpload) already parsed
            // the multipart body. It stores the result as request attribute "fileUpload".
            // Exact API confirmed via javap on jahia-impl-8.2.2.1.jar:
            //   Set<String> getFileNames()              - original submitted file names
            //   String      getFormFieldName(fileName)  - the HTML field name for that file
            //   String      getFileContentType(fileName)
            //   File        getFile(fileName)           - temp file on disk
            Object fu = request.getAttribute("fileUpload");
            if (fu != null) {
                try {
                    @SuppressWarnings("unchecked")
                    java.util.Set<String> fileNames =
                            (java.util.Set<String>) fu.getClass().getMethod("getFileNames").invoke(fu);

                    for (String fileName : fileNames) {
                        String fieldName = (String) fu.getClass()
                                .getMethod("getFormFieldName", String.class).invoke(fu, fileName);
                        if (!"documentFile".equals(fieldName)) continue;

                        documentMime = (String) fu.getClass()
                                .getMethod("getFileContentType", String.class).invoke(fu, fileName);

                        java.io.File tmpFile = (java.io.File) fu.getClass()
                                .getMethod("getFile", String.class).invoke(fu, fileName);
                        byte[] bytes = java.nio.file.Files.readAllBytes(tmpFile.toPath());
                        documentBase64 = Base64.getEncoder().encodeToString(bytes);
                        log.info("GeneratePageAction: document '{}' field='{}' {} bytes type={}.",
                                fileName, fieldName, bytes.length, documentMime);
                        break;
                    }
                } catch (Exception e) {
                    log.warn("GeneratePageAction: failed reading file from FileUpload: {}", e.getMessage(), e);
                }
            }

            if (documentBase64 == null) {
                log.info("GeneratePageAction: no document received (fileUpload attr={}).",
                        fu != null ? fu.getClass().getSimpleName() : "null");
            }

            String structureJson = aiService.generatePageStructure(
                    prompt, audience, tone, documentBase64, documentMime, urls);

            resp.put("success", true);
            resp.put("structureJson", structureJson);
            return writeJson(renderContext, HttpServletResponse.SC_OK, resp);

        } catch (RateLimitExceededException e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            return writeJson(renderContext, 429, resp);

        } catch (IllegalArgumentException e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            return writeJson(renderContext, HttpServletResponse.SC_BAD_REQUEST, resp);

        } catch (Exception e) {
            log.error("GeneratePageAction failed.", e);
            resp.put("success", false);
            resp.put("error", "Generation failed. Please try again.");
            return writeJson(renderContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String coalesce(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String single(Map<String, List<String>> params, String name) {
        List<String> list = params.getOrDefault(name, Collections.emptyList());
        return list.isEmpty() ? null : list.get(0);
    }

    private static List<String> splitUrls(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    private static ActionResult writeJson(RenderContext ctx, int status, JSONObject body) throws Exception {
        HttpServletResponse response = ctx.getResponse();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().print(body.toString());
        // Return OK so Jahia doesn’t forward to its HTML error page
        // (status is already set on the response object above)
        return ActionResult.OK;
    }
}
