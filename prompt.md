# Revised Prompt — Jahia AI Landing Page Generator

## Role & Context
I'm a senior fullstack developer at Jahia with expertise in Java OSGi and React.js. I'm building a Jahia UI extension, packaged as a Maven OSGi bundle, that lets content authors generate full landing pages from an AI prompt.

## Current State
The module has already been scaffolded at `/Users/stephane/Runtimes/0.Modules/ai-landing-page-generation`. Existing artifacts:
- [pom.xml](pom.xml) — parent `org.jahia.modules:jahia-modules:8.2.0.0`, packaging `bundle`, `jahia-module-type: system`, frontend-maven-plugin wired for Yarn build.
- [package.json](package.json), [webpack.config.js](webpack.config.js), [babel.config.js](babel.config.js) — React build chain.
- [schema.graphql](schema.graphql) — at the project root, used for Jahia queries/mutations and module schema extensions.
- [src/main](src/main/) and [src/javascript](src/javascript/) directories (empty or minimal).

**Do not re-scaffold.** Work inside this tree.

## POM Alignment
Update [pom.xml](pom.xml) to match the structure of Jahia's reference module POM: [Jahia/jcontent/pom.xml](https://github.com/Jahia/jcontent/blob/master/pom.xml). Specifically bring in:
- Parent version bump to `8.2.1.0` (or the current jcontent parent).
- `<scm>` block.
- `jahia-depends` property — at minimum `jahia-ui-root`, `app-shell`, `graphql-dxm-provider` (use the same versions jcontent pins, unless a newer compatible release is available).
- `import-package` / `export-package` declarations covering: `org.jahia.modules.graphql.provider.dxm.*`, `graphql.annotations.annotationTypes`, Jackson, RxJava, JCR, Jahia services, OWASP html-sanitizer, `org.osgi.framework`, SLF4J, Spring context — trim to only what this module actually uses.
- Dependencies: `graphql-dxm-provider`, `graphql-java-annotations`, Jackson (core/databind/annotations/jaxb), `rxjava` 2.x, `graphql-java-servlet`, `owasp-java-html-sanitizer`, Guava, JUnit, Hamcrest, `org.jahia.test:module-test-framework` — scope `provided` for runtime-supplied libs, `test` for tests.
- Upgrade `frontend-maven-plugin` to `1.13.4`, Node to `v24.12.0`, Yarn to `1.22.22`.
- `require-capability` for the blueprint extender.

## Target Environment
- **Jahia version:** 8.2
- **Maven coordinates:** `groupId: org.jahia.se.modules`, `artifactId: ai-landing-page-generation`, version `1.0.0-SNAPSHOT`
- **Bundle symbolic name:** `ai-landing-page-generation`
- **Reference implementation:** [smonier/anthropic-tags](https://github.com/smonier/anthropic-tags)
- **Reference POM:** [Jahia/jcontent](https://github.com/Jahia/jcontent/blob/master/pom.xml)

## Goal
Expose a new action in jContent (`targets: ['contentActions:999']`) on an existing page. When triggered, it opens a dialog where the content author can:

1. **Enter a natural-language prompt**, for example:
   > "Create a landing page with a hero banner, a 1st row with 2 columns, a 2nd row with 1 column, a 3rd row with 3 columns. Use the following copy: [...]. Organize it in a modern, creative, and attractive — yet classic — style."
2. **Attach a document or URLs** to provide additional context.
   - Accepted file types: PDF, DOCX, TXT, MD (max 10 MB).
   - URLs fetched **server-side only** (to avoid CORS and SSRF); enforce host allow-list, timeout, size cap.
3. **Select a target audience** from a dropdown (e.g. IT, Finance, Marketing).
4. **Select a tone** from a dropdown — audience + tone are injected into the final system prompt.

### Generation UX
- **Streaming response** with progress UI in the dialog.
- **Preview & confirm step**: generated structure shown to the author before persistence. Regenerate, tweak, or accept.
- On accept, the page is materialized under the current node as a **draft** (not published).
- **Failure semantics**: the GraphQL mutation that creates the page tree is transactional — partial failure rolls back everything.

## Structural Jahia Components
Create component bundles for both **front-end** (views, CSS, assets) and **back-end** (CND definitions, Java services).

| Component | Notes |
|---|---|
| `jnt:page` | Standard Jahia page |
| `GridRow` | Contribution area with 1–4 columns. Ref: [mdl-templates/Cols](https://github.com/smonier/mdl-templates/tree/customCss/src/components/Cols) |
| `HeroBanner` | With CTAs. Ref: [mdl-templates/Hero](https://github.com/smonier/mdl-templates/tree/main/src/components/Hero) |
| `RichText` | Ref: [mdl-templates/FreeText](https://github.com/smonier/mdl-templates/tree/main/src/components/FreeText) |
| Image Reference | Standard Jahia component. AI output provides alt text + placeholder; author picks the actual image afterward. |
| `CallToAction` | Button component with label, URL, target, style variant. |

For each component define: CND node type (properties, child-node constraints, mixins such as `jmix:droppableContent`, `jmix:editorialContent`), views, and i18n resource bundles (`en`, `fr` minimum).

## Architecture

### Front-end (React)
- Dialog built with **Moonstone** components to match jContent.
- Action registered via the Jahia UI-extension API, target `contentActions:999`.
- Talks **only** to the module's own GraphQL endpoint — never to the AI provider directly.

### Back-end (Java / GraphQL)
- `schema.graphql` at the project root defines the module's queries and mutations.
- A **Java/GraphQL service layer** brokers every call to the AI provider:
  - Holds the API key (server-side only).
  - Assembles the final prompt from user input + audience + tone + ingested document/URL context.
  - Enforces auth, permission re-check, rate-limiting, token caps.
  - Hardens against prompt injection in user input and fetched URL content (isolate untrusted text in the system prompt).
  - Materializes the generated page through Jahia's GraphQL mutations (create nodes, set properties, build the component tree).

### AI Provider
- **Provider:** Anthropic Claude
- **Default model:** `claude-sonnet-4-6` (configurable)
- Streaming enabled.

## OSGi Configuration (`.cfg`)
```
ai.provider=anthropic
ai.apiKey=<secret>
ai.model=claude-sonnet-4-6
ai.maxInputTokens=100000
ai.maxOutputTokens=8000
ai.timeoutMs=60000
ai.rateLimit.perUserPerMinute=5
ai.urlFetch.allowedHosts=
ai.urlFetch.maxSizeBytes=5242880
audiences=IT,Finance,Marketing,Sales,HR
tones=Professional,Friendly,Bold,Playful,Authoritative
```
Template path: `src/main/resources/META-INF/configurations/org.jahia.se.modules.ailandingpagegenerator.cfg`.

## Security & Permissions
- Action visible only to users with the `editor` role or stronger.
- GraphQL mutation re-checks permissions server-side.
- All user input, fetched URL content, and uploaded documents are treated as untrusted and sandboxed in the system prompt.
- Standard CSRF protections on the GraphQL endpoint.

## Observability
Log per request (server-side, no content leakage): user, timestamp, duration, model, input/output token counts, success/failure, error class. Log a **hash** of the final prompt, not the prompt itself.

## Testing
- Unit tests for the prompt assembler (audience + tone + context merging).
- Integration test for the page-creation GraphQL mutation.
- AI calls mocked via a recorded fixture — CI never hits the real provider.

## Deliverable
1. **Updated [pom.xml](pom.xml)** aligned with [Jahia/jcontent](https://github.com/Jahia/jcontent/blob/master/pom.xml).
2. **Back-end** under `src/main/java/org/jahia/se/modules/ailandingpagegenerator/`: GraphQL service, prompt assembler, AI client, document/URL ingestion, page-materialization mutations.
3. **Front-end** under `src/javascript/`: React dialog (Moonstone), action registration, streaming UI, preview/confirm flow, Apollo client calls to the module's GraphQL endpoint.
4. **Component bundles** (CND + views + i18n) for `GridRow`, `HeroBanner`, `RichText`, `CallToAction`; wiring for the standard Image Reference.
5. **Schema additions** to `schema.graphql` for the module's queries/mutations.
6. **OSGi `.cfg`** template with the keys above.
7. **Tests** (unit + integration, AI mocked).
8. **README** with install/deploy steps into a Jahia 8.2 instance.