package org.jahia.se.modules.ailandingpagegenerator.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.se.modules.ailandingpagegenerator.service.AiLandingPageService;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;

/**
 * Returns the dialog configuration (audiences, tones) as JSON.
 *
 * Registered as action name: {@code aiLandingPageGetConfig}
 * Accepts GET requests from any authenticated user.
 */
@Component(service = Action.class)
public class GetConfigAction extends Action {

    private AiLandingPageService aiService;

    @Activate
    public void activate() {
        setName("aiLandingPageGetConfig");
        setRequireAuthenticatedUser(true);
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
        resp.put("audiences", aiService.getAudiences());
        resp.put("tones", aiService.getTones());
        resp.put("providers", new JSONArray(aiService.getAvailableProviders()));

        HttpServletResponse response = renderContext.getResponse();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        writer.print(resp.toString());
        writer.flush();
        return new ActionResult(HttpServletResponse.SC_OK, null, null);
    }
}
