package org.jahia.se.modules.ailandingpagegenerator.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.content.nodetypes.ExtendedNodeType;
import org.jahia.services.content.nodetypes.ExtendedPropertyDefinition;
import org.jahia.services.content.nodetypes.NodeTypeRegistry;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializer;
import org.jahia.services.content.nodetypes.initializers.ChoiceListInitializerService;
import org.jahia.services.content.nodetypes.initializers.ChoiceListValue;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONArray;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Returns the page templates available for the current site as JSON.
 *
 * Uses ChoiceListInitializerService with the "templates" initializer — the same
 * mechanism as the Content Editor j:templateName dropdown — so it works for both
 * OSGi (jnt:pageTemplate) and JS-module templates.
 *
 * Response JSON:
 *   { "templates": [ { "name": "free", "title": "Free Design" }, ... ] }
 */
@Component(service = Action.class)
public class GetTemplatesAction extends Action {

    private static final Logger log = LoggerFactory.getLogger(GetTemplatesAction.class);

    @Activate
    public void activate() {
        setName("aiLandingPageGetTemplates");
        setRequireAuthenticatedUser(true);
        setRequiredWorkspace("default");
        setRequiredMethods("POST");
    }

    @Override
    public ActionResult doExecute(
            HttpServletRequest request,
            RenderContext renderContext,
            Resource resource,
            JCRSessionWrapper session,
            Map<String, List<String>> parameters,
            URLResolver urlResolver) throws Exception {

        JSONArray templates = new JSONArray();

        try {
            JCRNodeWrapper siteNode = resource.getNode().getResolveSite();
            Locale locale = renderContext.getMainResourceLocale();

            // Build context exactly as TemplatesChoiceListInitializerImpl expects it.
            // contextNode  → used to resolve site
            // contextType  → drives the nodeTypeList (we want jnt:page templates)
            ExtendedNodeType pageNodeType = NodeTypeRegistry.getInstance().getNodeType("jnt:page");
            // The EPD is required — initializer calls declaringPropertyDefinition.getResourceBundleKey()
            ExtendedPropertyDefinition epd = pageNodeType.getPropertyDefinition("j:templateName");

            Map<String, Object> context = new HashMap<>();
            context.put("contextNode", siteNode);
            context.put("contextType", pageNodeType);

            ChoiceListInitializer initializer =
                    ChoiceListInitializerService.getInstance().getInitializers().get("templates");

            if (initializer == null) {
                log.warn("GetTemplatesAction: 'templates' ChoiceListInitializer not found");
            } else {
                // param="" → no extra filter (same as the j:templateName property definition)
                List<ChoiceListValue> choices = initializer.getChoiceListValues(
                        epd, "", Collections.emptyList(), locale, context);

                log.warn("GetTemplatesAction: site={} found {} template choices",
                        siteNode.getName(), choices.size());

                for (ChoiceListValue choice : choices) {
                    String name  = choice.getValue().getString();
                    String title = choice.getDisplayName();
                    if (title == null || title.isBlank()) {
                        title = name;
                    }
                    JSONObject entry = new JSONObject();
                    entry.put("name", name);
                    entry.put("title", title);
                    templates.put(entry);
                    log.warn("GetTemplatesAction: template name={} title={}", name, title);
                }
            }
        } catch (Exception e) {
            log.warn("GetTemplatesAction: error fetching templates.", e);
        }

        JSONObject resp = new JSONObject();
        resp.put("templates", templates);

        HttpServletResponse response = renderContext.getResponse();
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=UTF-8");
        PrintWriter writer = response.getWriter();
        writer.print(resp.toString());
        writer.flush();
        return new ActionResult(HttpServletResponse.SC_OK, null, null);
    }
}
