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
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Jahia Action that calls the AI service to generate a landing-page JSON structure.
 *
 * Registered as action name: {@code generatePageAction}
 * Required permission: {@code jcr:write_default} (editor role or stronger)
 *
 * Request parameters (POST, application/x-www-form-urlencoded):
 *   prompt           – natural-language description (required)
 *   audience         – selected audience key (required)
 *   tone             – selected tone key (required)
 *   documentBase64   – base-64 encoded document (optional)
 *   documentMimeType – MIME type of the document (optional)
 *   urls             – comma-separated list of URLs (optional)
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
            String prompt          = required(parameters, "prompt");
            String audience        = required(parameters, "audience");
            String tone            = required(parameters, "tone");
            String documentBase64  = single(parameters, "documentBase64");
            String documentMime    = single(parameters, "documentMimeType");
            List<String> urls      = splitUrls(single(parameters, "urls"));

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

    private static String required(Map<String, List<String>> params, String name) {
        String v = single(params, name);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Required parameter missing: " + name);
        }
        return v;
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
                .toList();
    }

    private static ActionResult writeJson(RenderContext ctx, int status, JSONObject body) throws Exception {
        HttpServletResponse response = ctx.getResponse();
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        writer.print(body.toString());
        writer.flush();
        return new ActionResult(status, null, null);
    }
}
