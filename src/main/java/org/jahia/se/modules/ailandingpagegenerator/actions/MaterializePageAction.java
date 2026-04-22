package org.jahia.se.modules.ailandingpagegenerator.actions;

import org.jahia.bin.Action;
import org.jahia.bin.ActionResult;
import org.jahia.se.modules.ailandingpagegenerator.service.UnsplashService;
import org.jahia.services.content.JCRNodeWrapper;
import org.jahia.services.content.JCRSessionWrapper;
import org.jahia.services.render.RenderContext;
import org.jahia.services.render.Resource;
import org.jahia.services.render.URLResolver;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Binary;
import javax.jcr.PathNotFoundException;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Jahia Action that materializes the AI-generated page structure into JCR.
 *
 * Registered as action name: {@code materializePageAction}
 * Required permission: {@code jcr:write_default} (editor role or stronger)
 *
 * Request parameters (POST):
 *   structureJson – the JSON string produced by GeneratePageAction (required)
 *   pageTitle     – title for the new page node (required)
 *   templateName  – j:templateName value for the new jnt:page node (required)
 *
 * The page is created as a DRAFT (not published) under the current node.
 * The operation is transactional: if any child node creation fails, the
 * entire JCR session is discarded (no partial commit).
 *
 * Response JSON:
 *   { "success": true, "nodePath": "/path/to/new/page" }
 *   { "success": false, "error": "..." }
 */
@Component(service = Action.class)
public class MaterializePageAction extends Action {

    private static final Logger log = LoggerFactory.getLogger(MaterializePageAction.class);

    /** Optional — materialization succeeds even when Unsplash is not configured. */
    @Reference(service = UnsplashService.class,
               cardinality = ReferenceCardinality.OPTIONAL,
               policy = ReferencePolicy.DYNAMIC)
    private volatile UnsplashService unsplashService;

    @Activate
    public void activate() {
        setName("materializePageAction");
        setRequireAuthenticatedUser(true);
        setRequiredPermission("jcr:write_default");
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

        JSONObject resp = new JSONObject();

        try {
            String structureJson = required(parameters, "structureJson");
            String pageTitle     = required(parameters, "pageTitle");
            String templateName  = required(parameters, "templateName");

            // Re-check permission (defence in depth)
            JCRNodeWrapper parent = resource.getNode();
            if (!parent.hasPermission("jcr:addChildNodes")) {
                resp.put("success", false);
                resp.put("error", "Insufficient permissions to create page under " + parent.getPath());
                return new ActionResult(HttpServletResponse.SC_FORBIDDEN, null, resp);
            }

            JSONObject structure;
            try {
                structure = new JSONObject(structureJson);
            } catch (JSONException e) {
                resp.put("success", false);
                resp.put("error", "Invalid JSON structure from AI generation.");
                return new ActionResult(HttpServletResponse.SC_BAD_REQUEST, null, resp);
            }

            // Derive JCR path for image storage from the parent node's site
            String siteKey = extractSiteKey(parent);
            String imagesPath = siteKey != null
                    ? "/sites/" + siteKey + "/files/landingPages/images"
                    : null;

            // All mutations happen in the same session — rollback on any exception
            String nodePath;
            try {
                nodePath = createPageTree(session, parent, pageTitle, templateName, structure, imagesPath);
                session.save();   // single commit — all or nothing
            } catch (Exception e) {
                // Discard any partial changes in this session
                try { session.refresh(false); } catch (RepositoryException re) { /* ignore */ }
                throw e;
            }

            resp.put("success", true);
            resp.put("nodePath", nodePath);
            return writeJson(renderContext, HttpServletResponse.SC_OK, resp);

        } catch (IllegalArgumentException e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            return writeJson(renderContext, HttpServletResponse.SC_BAD_REQUEST, resp);

        } catch (Exception e) {
            log.error("MaterializePageAction failed.", e);
            resp.put("success", false);
            resp.put("error", "Page creation failed. Please try again.");
            return writeJson(renderContext, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, resp);
        }
    }

    // ── page tree builder ─────────────────────────────────────────────────────

    /**
     * Recursively materializes the JSON component tree into JCR nodes.
     *
     * @return the absolute path of the created page node
     */
    private String createPageTree(
            JCRSessionWrapper session,
            JCRNodeWrapper parent,
            String pageTitle,
            String templateName,
            JSONObject structure,
            String imagesPath) throws RepositoryException, JSONException {

        // Sanitize the page title for use as a node name
        String nodeName = toNodeName(pageTitle);

        // Create the jnt:page node
        JCRNodeWrapper pageNode = parent.addNode(nodeName, "jnt:page");
        pageNode.setProperty("jcr:title", pageTitle);
        pageNode.setProperty("j:templateName", templateName);

        // Create the "main" area (jnt:contentList) — matches the area name in the template
        JCRNodeWrapper mainArea = pageNode.addNode("main", "jnt:contentList");

        // Recursively create children inside the "main" area
        if (structure.has("children")) {
            JSONArray children = structure.getJSONArray("children");
            for (int i = 0; i < children.length(); i++) {
                JSONObject child = children.getJSONObject(i);
                createComponentNode(session, mainArea, child, imagesPath);
            }
        }

        return pageNode.getPath();
    }

    private void createComponentNode(
            JCRSessionWrapper session,
            JCRNodeWrapper parent,
            JSONObject component,
            String imagesPath) throws RepositoryException, JSONException {

        String type = component.optString("type", "ailp:richText");
        String name = uniqueName(type);

        switch (type) {
            case "ailp:heroBanner" -> {
                JCRNodeWrapper node = parent.addNode(name, "ailp:heroBanner");
                setIfPresent(node, "headline",               component.optString("headline", ""));
                setIfPresent(node, "subheadline",            component.optString("subheadline", ""));
                String bgAltText = component.optString("backgroundImageAltText", "");
                setIfPresent(node, "backgroundImageAltText", bgAltText);
                // Fetch image from Unsplash and store in JCR
                linkImage(session, node, "backgroundImage", bgAltText, imagesPath);
                // ailp:heroBanner accepts ailp:callToAction children directly
                createCtaChildren(node, component);
            }
            case "ailp:textImage" -> {
                JCRNodeWrapper node = parent.addNode(name, "ailp:textImage");
                setIfPresent(node, "title",         component.optString("title", ""));
                setIfPresent(node, "text",          component.optString("content",
                                                    component.optString("text", "")));
                String imgAltText = component.optString("imageAltText", "");
                setIfPresent(node, "imageAltText",  imgAltText);
                setIfPresent(node, "imagePosition", component.optString("imagePosition", ""));
                // Fetch image from Unsplash and store in JCR
                linkImage(session, node, "image", imgAltText, imagesPath);
                // ailp:textImage also accepts ailp:callToAction children directly
                createCtaChildren(node, component);
            }
            case "ailp:gridRow" -> {
                JCRNodeWrapper node = parent.addNode(name, "ailp:gridRow");
                int columns = Math.max(1, Math.min(4, component.optInt("columns", 2)));
                node.setProperty("columns", (long) columns);
                // ailp:gridRow accepts any jmix:droppableContent (ailpmix:component) children
                if (component.has("children")) {
                    JSONArray children = component.getJSONArray("children");
                    for (int i = 0; i < children.length(); i++) {
                        createComponentNode(session, node, children.getJSONObject(i), imagesPath);
                    }
                }
            }
            case "ailp:richText" -> {
                JCRNodeWrapper node = parent.addNode(name, "ailp:richText");
                String content = component.optString("content",
                                 component.optString("text", ""));
                if (!content.isBlank()) {
                    node.setProperty("content", "<p>" + content.trim() + "</p>");
                }
            }
            case "ailp:quote" -> {
                JCRNodeWrapper node = parent.addNode(name, "ailp:quote");
                String text = component.optString("text",
                              component.optString("content", "Quote"));
                node.setProperty("text", text);   // mandatory
                setIfPresent(node, "attribution", component.optString("attribution",
                                                  component.optString("author", "")));
                setIfPresent(node, "variant",     component.optString("variant", ""));
            }
            case "ailp:testimonial" -> {
                JCRNodeWrapper node = parent.addNode(name, "ailp:testimonial");
                String quote = component.optString("quote",
                               component.optString("content", "Testimonial"));
                String author = component.optString("authorName",
                                component.optString("author", "Author"));
                node.setProperty("quote",      quote);   // mandatory
                node.setProperty("authorName", author);  // mandatory
                setIfPresent(node, "authorRole",    component.optString("authorRole", ""));
                setIfPresent(node, "authorCompany", component.optString("authorCompany", ""));
            }
            case "ailp:card" -> {
                JCRNodeWrapper node = parent.addNode(name, "ailp:card");
                setIfPresent(node, "title",      component.optString("title", ""));
                String content = component.optString("content",
                                 component.optString("text", ""));
                if (!content.isBlank()) {
                    node.setProperty("content", content);
                }
                setIfPresent(node, "ctaLabel",   component.optString("ctaLabel", ""));
                setIfPresent(node, "ctaUrl",     component.optString("ctaUrl",
                                                 component.optString("url", "")));
                setIfPresent(node, "ctaTarget",  component.optString("ctaTarget", ""));
                setIfPresent(node, "ctaVariant", component.optString("ctaVariant", ""));
            }
            case "ailp:callToAction" -> {
                // ailp:callToAction is not droppable (no ailpmix:component mixin).
                // It cannot be added directly to jnt:contentList — convert to richText.
                log.warn("MaterializePageAction: ailp:callToAction at top level is not " +
                         "droppable; converting to ailp:richText.");
                JCRNodeWrapper node = parent.addNode(name, "ailp:richText");
                String label = component.optString("label", "Call to action");
                String url   = component.optString("url", "#");
                node.setProperty("content", "<p><a href=\"" + url + "\">" + label + "</a></p>");
            }
            default -> {
                log.warn("MaterializePageAction: unknown component type '{}', falling back to ailp:richText.", type);
                JCRNodeWrapper node = parent.addNode(uniqueName("ailp:richText"), "ailp:richText");
                String content = component.optString("content",
                                 component.optString("text",
                                 component.optString("headline", "")));
                if (!content.isBlank()) {
                    node.setProperty("content", "<p>" + content.trim() + "</p>");
                }
            }
        }
    }

    /**
     * Adds {@code ailp:callToAction} child nodes directly under {@code parent}
     * (a heroBanner or textImage node, which both declare {@code + * (ailp:callToAction)}).
     */
    private void createCtaChildren(
            JCRNodeWrapper parent,
            JSONObject component) throws RepositoryException, JSONException {

        if (!component.has("children")) return;
        JSONArray children = component.getJSONArray("children");
        for (int i = 0; i < children.length(); i++) {
            JSONObject child = children.getJSONObject(i);
            if (!"ailp:callToAction".equals(child.optString("type", ""))) continue;
            JCRNodeWrapper cta = parent.addNode(uniqueName("ailp:callToAction"), "ailp:callToAction");
            String label = child.optString("label", "Learn more");
            String url   = child.optString("url", "#");
            cta.setProperty("label", label);  // mandatory
            cta.setProperty("url",   url);    // mandatory
            setIfPresent(cta, "target",       child.optString("target", ""));
            setIfPresent(cta, "styleVariant", child.optString("styleVariant", ""));
        }
    }

    // ── image helpers ─────────────────────────────────────────────────────────

    /**
     * Fetches an image from Unsplash for {@code query}, stores it as a
     * {@code jnt:file} node under {@code imagesPath}, and sets a weak reference
     * on {@code node.prop}.
     *
     * This is best-effort: any failure is logged and silently swallowed so that
     * the overall page materialization is not aborted.
     */
    private void linkImage(
            JCRSessionWrapper session,
            JCRNodeWrapper node,
            String prop,
            String query,
            String imagesPath) {

        UnsplashService svc = unsplashService;  // capture volatile reference
        if (svc == null || query == null || query.isBlank() || imagesPath == null) return;

        UnsplashService.ImageData data = svc.fetchImage(query);
        if (data == null) return;

        try {
            JCRNodeWrapper folder = ensureFolder(session, imagesPath);

            // Avoid name collisions for duplicate alt-text queries
            String fileName = data.fileName();
            if (folder.hasNode(fileName)) {
                fileName = UUID.randomUUID().toString().substring(0, 8) + "-" + fileName;
            }

            // Create jnt:file + jcr:content
            JCRNodeWrapper fileNode    = folder.addNode(fileName, "jnt:file");
            // jmix:image mixin required by ailp:heroBanner/textImage image property constraints
            fileNode.addMixin("jmix:image");
            JCRNodeWrapper contentNode = fileNode.addNode("jcr:content", "jnt:resource");

            Binary binary = session.getValueFactory()
                    .createBinary(new ByteArrayInputStream(data.bytes()));
            contentNode.setProperty("jcr:data",         binary);
            contentNode.setProperty("jcr:mimeType",     data.mimeType());
            contentNode.setProperty("jcr:lastModified", Calendar.getInstance());

            // Set weak reference on the component node
            Value weakRef = session.getValueFactory()
                    .createValue(fileNode.getIdentifier(), PropertyType.WEAKREFERENCE);
            node.setProperty(prop, weakRef);

            log.info("MaterializePageAction: stored Unsplash image '{}' (by {}) at {} for prop '{}'.",
                    fileName, data.photographer(), fileNode.getPath(), prop);

        } catch (Exception e) {
            log.warn("MaterializePageAction: failed to store image for prop '{}' query '{}': {}",
                    prop, query, e.getMessage());
        }
    }

    /**
     * Navigates to the JCR node at the given absolute path, creating any missing
     * intermediate folder nodes as {@code jnt:contentFolder}.
     */
    private static JCRNodeWrapper ensureFolder(JCRSessionWrapper session, String path)
            throws RepositoryException {
        try {
            return (JCRNodeWrapper) session.getNode(path);
        } catch (PathNotFoundException e) {
            int slash = path.lastIndexOf('/');
            String parentPath = path.substring(0, slash);
            String name       = path.substring(slash + 1);
            JCRNodeWrapper parent = ensureFolder(session, parentPath);
            return parent.addNode(name, "jnt:folder");
        }
    }

    /** Extracts the site key from a JCR path of the form /sites/{siteKey}/... */
    private static String extractSiteKey(JCRNodeWrapper node) {
        String[] parts = node.getPath().split("/");
        return (parts.length >= 3 && "sites".equals(parts[1])) ? parts[2] : null;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void setIfPresent(JCRNodeWrapper node, String prop, String value)
            throws RepositoryException {
        if (value != null && !value.isBlank()) {
            node.setProperty(prop, value);
        }
    }

    /** Converts a page title to a safe JCR node name. */
    private static String toNodeName(String title) {
        String name = title.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return name.substring(0, Math.min(64, name.length()));
    }

    /** Generates a unique node name for a component to avoid collisions. */
    private static String uniqueName(String type) {
        String base = type.contains(":") ? type.substring(type.indexOf(':') + 1) : type;
        return base + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String required(Map<String, List<String>> params, String name) {
        List<String> list = params.getOrDefault(name, Collections.emptyList());
        if (list.isEmpty() || list.get(0).isBlank()) {
            throw new IllegalArgumentException("Required parameter missing: " + name);
        }
        return list.get(0);
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
