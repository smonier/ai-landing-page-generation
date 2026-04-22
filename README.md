# AI Landing Page Generation

A Jahia 8.2+ OSGi module that lets content authors generate complete, structured landing pages from a natural-language prompt. Powered by Anthropic Claude, with optional automatic image fetching via the Unsplash API.

---

## Table of Contents

1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Architecture](#architecture)
4. [Backend Components](#backend-components)
   - [Actions](#actions)
   - [Services](#services)
   - [Prompt Assembly](#prompt-assembly)
   - [Document & URL Ingestion](#document--url-ingestion)
   - [Security](#security)
   - [Observability](#observability)
5. [Frontend Components](#frontend-components)
6. [Component Schema (ailp:\*)](#component-schema-ailp)
7. [JCR Structure](#jcr-structure)
8. [Configuration Reference](#configuration-reference)
9. [Build & Deploy](#build--deploy)
10. [User Flow](#user-flow)
11. [Security Considerations](#security-considerations)

---

## Overview

Content authors right-click any `jnt:page` or navigation item in jContent and choose **Generate Landing Page**. A dialog collects:

- A **natural-language prompt** describing the desired page
- An optional **attached document** (PDF, DOCX, TXT, MD — max 10 MB) for extra context
- Optional **reference URLs** (fetched server-side) for additional context
- A **target audience** (IT, Finance, Marketing, …)
- A **tone** (Professional, Friendly, Bold, …)
- A **page template** (populated from the Jahia template engine)

The module sends the assembled prompt to **Anthropic Claude**, receives a structured JSON response describing the page, shows a **preview** to the author, and — on confirmation — **materialises the entire page tree** in JCR as a draft (not published). If Unsplash is configured, landscape photos are automatically fetched for `ailp:heroBanner` and `ailp:textImage` components and stored as proper JCR files.

---

## Prerequisites

| Requirement | Version |
|---|---|
| Jahia DX / Content Cloud | 8.2.2+ |
| Java | 17 |
| Maven | 3.8+ |
| Node.js (build only) | 24+ |
| Yarn (build only) | 1.22+ |
| `ai-landing-page-components` module | deployed in target Jahia |
| Anthropic API key | required |
| Unsplash API access key | optional (skipped gracefully when absent) |

> **Dependency**: All `ailp:*` content node types (heroBanner, textImage, gridRow, richText, quote, testimonial, callToAction, card) are defined in the companion module **`ai-landing-page-components`**, which must be deployed first.

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│  jContent (browser)                                                  │
│                                                                      │
│  AiLandingPageAction ──opens──► AiLandingPageDialog                 │
│       (contentActions:999)       │                                   │
│                                  │ 1. POST /generatePageAction       │
│                                  │ 2. POST /materializePageAction    │
└──────────────────────────────────┼───────────────────────────────────┘
                                   │ HTTP (Jahia Actions)
┌──────────────────────────────────▼───────────────────────────────────┐
│  Java / OSGi layer                                                   │
│                                                                      │
│  GeneratePageAction                                                  │
│    └─► AiLandingPageServiceImpl                                      │
│          ├─► RateLimiter          (per-user, per-minute quota)       │
│          ├─► DocumentIngestionService  (base-64 → plain text)        │
│          ├─► UrlFetchService      (server-side fetch + allow-list)   │
│          ├─► PromptAssembler      (trusted + untrusted sections)     │
│          ├─► Anthropic Claude SDK (claude-sonnet-4-6)                │
│          └─► RequestLogger        (timing, token counts, hash)       │
│                                                                      │
│  MaterializePageAction                                               │
│    ├─► JCR session (all-or-nothing commit)                           │
│    └─► UnsplashService  (optional image fetch → jnt:file)            │
│                                                                      │
│  GetConfigAction    (audiences, tones)                               │
│  GetTemplatesAction (available page templates)                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Backend Components

### Actions

All actions extend `org.jahia.bin.Action`, are registered as OSGi services, require an authenticated user, and enforce `jcr:write_default` permission.

#### `GeneratePageAction`

**Action name**: `generatePageAction` | **Method**: POST

Orchestrates AI generation.

| Parameter | Required | Description |
|---|---|---|
| `prompt` | ✅ | Natural-language page description |
| `audience` | ✅ | Selected audience key |
| `tone` | ✅ | Selected tone key |
| `documentBase64` | ✗ | Base-64 encoded document |
| `documentMimeType` | ✗ | MIME type of the document |
| `urls` | ✗ | Comma-separated reference URLs |

Returns `{ "structureJson": "...", "success": true }` or `{ "error": "...", "success": false }`.

#### `MaterializePageAction`

**Action name**: `materializePageAction` | **Method**: POST

Creates the full JCR page tree from the AI-generated JSON. All mutations happen in a single JCR session — a partial failure discards the entire operation (no orphaned nodes).

| Parameter | Required | Description |
|---|---|---|
| `structureJson` | ✅ | JSON string from `GeneratePageAction` |
| `pageTitle` | ✅ | Title for the new `jnt:page` node |
| `templateName` | ✅ | `j:templateName` value |

Returns `{ "success": true, "nodePath": "/sites/.../..." }` or `{ "success": false, "error": "..." }`.

When `UnsplashService` is configured, landscape photos are automatically fetched for `ailp:heroBanner.backgroundImage` and `ailp:textImage.image` using the component's alt-text field as the search query. Images are stored under `/sites/{siteKey}/files/landingPages/images/` as `jnt:file` nodes with the `jmix:image` mixin, and the component properties reference them via `WEAKREFERENCE`.

#### `GetConfigAction`

**Action name**: `aiLandingPageGetConfig` | **Method**: POST

Returns the currently configured audience and tone lists so the dialog dropdowns always reflect the server-side `.cfg` values.

#### `GetTemplatesAction`

**Action name**: `aiLandingPageGetTemplates` | **Method**: POST

Returns the list of available `jnt:page` templates for the current site, used to populate the template selector in the dialog.

---

### Services

#### `AiLandingPageServiceImpl`

OSGi component implementing both `AiLandingPageService` and `ManagedService` (PID: `org.jahia.se.modules.ailandingpagegenerator`). Reads all AI-related configuration keys at runtime without restart.

Key responsibilities:
- Validates the API key is present before any generation attempt.
- Calls `RateLimiter` to enforce per-user quotas.
- Delegates document parsing and URL fetching to the ingestion layer.
- Delegates prompt construction to `PromptAssembler`.
- Calls the Anthropic SDK (`anthropic-java-client-okhttp`) and extracts the first text block from the response.
- Passes timing and token counts to `RequestLogger`.

#### `UnsplashServiceImpl`

OSGi component implementing both `UnsplashService` and `ManagedService` (same shared PID). Operates independently of the AI service — materialization succeeds even if this service is unconfigured (`@Reference` cardinality is `OPTIONAL`).

Flow for each image request:
1. **Search** `https://api.unsplash.com/search/photos?query={encoded}&per_page=1&orientation=landscape`
2. **Trigger download tracking** via `links.download_location` (required by Unsplash API guidelines)
3. **Download** the image binary (capped at 15 MB)
4. Return `ImageData(bytes, mimeType, fileName, photographerName)`

---

### Prompt Assembly

`PromptAssembler` builds the final prompt sent to Claude in two layers:

**Trusted system layer** — built from configuration (audience, tone) and the component schema definition. Never contains author-supplied text.

**Untrusted user layer** — the author's prompt, document content, and URL content, each wrapped in explicit `=== BEGIN UNTRUSTED … === / === END UNTRUSTED … ===` delimiters. The system prompt instructs the model to treat these sections as raw content/context only and to ignore any instructions found within them.

The combined prompt is truncated to stay within `AI_MAX_INPUT_TOKENS` (conservative 4 chars/token approximation). A SHA-256 hash of the full prompt is computed for observability.

---

### Document & URL Ingestion

#### `DocumentIngestionService`

Accepts base-64 encoded documents. Supported MIME types: `text/*`, `text/markdown`, PDF, DOCX (binary formats are accepted without extraction — the model receives no text context for them, gracefully). Size capped at 10 MB.

#### `UrlFetchService`

Fetches remote URLs **server-side only** (CORS-safe, no client exposure). Security controls:
- **Host allow-list**: only hosts listed in `AI_URL_FETCH_ALLOWED_HOSTS` are reachable. Empty list = all URL fetching disabled.
- **SSRF prevention**: host is validated after URI parsing; only HTTPS is permitted by default.
- **Response size cap**: `AI_URL_FETCH_MAX_SIZE_BYTES` (default 5 MB).
- **Timeout**: `AI_URL_FETCH_TIMEOUT_MS` (default 10 s).
- HTML is parsed with Jsoup to extract plain text before inclusion in the prompt.

---

### Security

#### `RateLimiter`

Per-user, per-minute token-bucket limiter backed by a `ConcurrentHashMap`. The window is rolling — it starts on the user's first request and resets 60 seconds later. Throws `RateLimitExceededException` when the limit is exceeded; `GeneratePageAction` returns HTTP 429 to the client.

Limit is configurable via `AI_RATE_LIMIT_PER_USER_PER_MINUTE` (default: 5).

---

### Observability

#### `RequestLogger`

Logs every generation request with: prompt hash, audience, tone, input/output token counts, latency, success/failure, and error class. No prompt text is logged (only its SHA-256 hash) to avoid leaking sensitive content to log files.

---

## Frontend Components

The UI is a React 18 application bundled with webpack 5 and Module Federation, registered into jContent via `@jahia/ui-extender`.

### Registration

`AiLandingPage.register.js` adds the action to `contentActions:999` (bottom of the right-click menu). Displayed only on `jnt:page` and `jnt:navMenuText` nodes when the user has `jcr:write_default`.

### `AiLandingPageAction`

Thin wrapper that uses `useNodeChecks` to validate node type and permission before rendering the trigger. Opens the dialog via `AiLandingPageDialogManager`.

### `AiLandingPageDialog`

Multi-step dialog (MUI 5 + Moonstone):

| Step | Description |
|---|---|
| `FORM` | Prompt, audience, tone, template, optional document / URLs |
| `LOADING` | Linear progress bar while the AI generates |
| `PREVIEW` | Raw JSON tree shown to the author; Regenerate / Accept buttons |
| `SUCCESS` | Confirmation with path to the created draft page |

Audiences, tones, and templates are loaded from the server on mount and fall back to hardcoded defaults if the request fails — so the UI always works even during a configuration reload.

---

## Component Schema (ailp:\*)

These types are defined in the `ai-landing-page-components` module. The AI is instructed to produce JSON matching this schema.

### `ailp:heroBanner`
Full-width hero section with headline, subheadline, background image, and CTA buttons.

| Property | Type | Notes |
|---|---|---|
| `headline` | string i18n | Main heading |
| `subheadline` | string i18n | Subtitle |
| `backgroundImage` | weakreference | `jmix:image` — set by Unsplash integration |
| `backgroundImageAltText` | string i18n | Alt text / Unsplash search query |
| Children | `ailp:callToAction` | CTA buttons |

### `ailp:textImage`
Side-by-side rich text and image block.

| Property | Type | Notes |
|---|---|---|
| `title` | string i18n | Section heading |
| `text` | richtext i18n | HTML body |
| `image` | weakreference | `jmix:image` — set by Unsplash integration |
| `imageAltText` | string i18n | Alt text / Unsplash search query |
| `imagePosition` | string | `left` or `right` |
| Children | `ailp:callToAction` | CTA buttons |

### `ailp:gridRow`
Responsive row with 1–4 equal columns. Any droppable `ailp:*` component can be a child.

| Property | Type | Notes |
|---|---|---|
| `columns` | long | 1–4 |
| Children | any `ailpmix:component` | One child object per column |

### `ailp:richText`
Free-form HTML rich text block.

| Property | Type |
|---|---|
| `content` | richtext i18n |

### `ailp:quote`
Styled blockquote.

| Property | Type | Notes |
|---|---|---|
| `text` | string i18n | Mandatory |
| `attribution` | string i18n | Author / source |
| `variant` | string | `block`, `pull`, or `centered` |

### `ailp:testimonial`
Testimonial card.

| Property | Type | Notes |
|---|---|---|
| `quote` | string i18n | Mandatory |
| `authorName` | string i18n | Mandatory |
| `authorRole` | string i18n | Job title |
| `authorCompany` | string i18n | Company name |

### `ailp:card`
Content card with a rich text body and an optional inline CTA button. Ideal for feature highlights, service cards, or pricing tiers — especially inside a `gridRow`.

| Property | Type | Notes |
|---|---|---|
| `title` | string i18n | Card heading |
| `content` | richtext i18n | HTML body |
| `ctaLabel` | string i18n | Button label |
| `ctaUrl` | string i18n | Destination URL |
| `ctaTarget` | string | `_self`, `_blank`, `_parent`, `_top` |
| `ctaVariant` | string | `primary`, `secondary`, `ghost` |

### `ailp:callToAction`
CTA button — **child-only**. Not droppable as a standalone top-level component; only valid as a child of `ailp:heroBanner` or `ailp:textImage`.

| Property | Type |
|---|---|
| `label` | string i18n |
| `url` | string i18n |
| `target` | string (`_self`, `_blank`, …) |
| `styleVariant` | string (`primary`, `secondary`, `ghost`) |

---

## JCR Structure

```
/sites/{siteKey}/
├── contents/
│   └── {parentPage}/
│       └── {new-page}                [jnt:page]
│           ├── j:templateName        = "{templateName}"
│           └── main                  [jnt:contentList]
│               ├── heroBanner-abc123 [ailp:heroBanner]
│               │   └── cta-def456   [ailp:callToAction]
│               ├── gridRow-ghi789   [ailp:gridRow]
│               │   ├── card-jkl012  [ailp:card]
│               │   └── richText-mno [ailp:richText]
│               └── …
│
└── files/
    └── landingPages/
        └── images/                   [jnt:folder]
            ├── unsplash-abc.jpg      [jnt:file + jmix:image]
            │   └── jcr:content       [jnt:resource]
            └── …
```

> Images are stored under `files/` (not `contents/`) so they are accessible to the Jahia DAM and satisfy the `< 'jmix:image'` constraint on image reference properties.

---

## Configuration Reference

**File**: `src/main/resources/META-INF/configurations/org.jahia.se.modules.ailandingpagegenerator.cfg`  
**Deploy to**: `<jahia-home>/digital-factory-data/karaf/etc/org.jahia.se.modules.ailandingpagegenerator.cfg`

All keys are hot-reloaded via OSGi `ManagedService` — no module restart required.

### Anthropic API

| Key | Default | Description |
|---|---|---|
| `AI_API_KEY` | — | **Required.** Anthropic API key. |
| `AI_API_BASE_URL` | `https://api.anthropic.com` | Base URL (override for proxies). |
| `AI_MODEL` | `claude-sonnet-4-6` | Claude model identifier. |
| `AI_MAX_INPUT_TOKENS` | `100000` | Token budget for the combined prompt. |
| `AI_MAX_OUTPUT_TOKENS` | `8000` | Max tokens the model may produce. |
| `AI_TIMEOUT_MS` | `60000` | HTTP timeout for the AI call (ms). |

### Rate Limiting

| Key | Default | Description |
|---|---|---|
| `AI_RATE_LIMIT_PER_USER_PER_MINUTE` | `5` | Max generation requests per user per minute. |

### URL Ingestion

| Key | Default | Description |
|---|---|---|
| `AI_URL_FETCH_ALLOWED_HOSTS` | _(empty)_ | Comma-separated hostnames. Empty = disabled. |
| `AI_URL_FETCH_MAX_SIZE_BYTES` | `5242880` | Max response size per URL (bytes). |
| `AI_URL_FETCH_TIMEOUT_MS` | `10000` | HTTP timeout for URL fetching (ms). |

### Unsplash Image Generation

| Key | Default | Description |
|---|---|---|
| `UNSPLASH_ACCESS_KEY` | _(empty)_ | Unsplash API access key. Leave empty to skip image generation. |

### UI

| Key | Default | Description |
|---|---|---|
| `AI_AUDIENCES` | `IT,Finance,Marketing,Sales,HR,C-Suite,General Public,Students,Developers` | Audience options in the dialog dropdown. |
| `AI_TONES` | `Professional,Friendly,Bold,Playful,Authoritative,Witty,Inspirational,Concise,Verbose` | Tone options in the dialog dropdown. |

---

## Build & Deploy

```bash
# Full build (lint + webpack + Maven)
mvn clean package

# Deploy to a running Jahia Docker container named jcontent-8221
docker cp target/*.jar jcontent-8221:/var/jahia/modules

# Combined one-liner (quiet Maven output)
mvn clean package -q && docker cp target/*.jar jcontent-8221:/var/jahia/modules
```

The frontend build is triggered automatically by `frontend-maven-plugin` during the Maven `generate-resources` phase (`yarn build:production`).

To iterate on the frontend without a full Maven build:

```bash
yarn dev    # webpack --watch
```

---

## User Flow

```
Author right-clicks page
        │
        ▼
[ Dialog: FORM step ]
  • Enter natural-language prompt
  • Select audience + tone
  • Select page template
  • (optional) attach document
  • (optional) enter reference URLs
        │
        ▼ click "Generate"
        │
[ Dialog: LOADING step ]
  POST /generatePageAction
  ├─ RateLimiter check
  ├─ Document parsing (if uploaded)
  ├─ URL fetching (if provided, allow-list enforced)
  ├─ Prompt assembly (trusted + untrusted sections)
  └─ Anthropic Claude API call
        │
        ▼ response received
        │
[ Dialog: PREVIEW step ]
  JSON tree displayed
  ├─ "Regenerate" → back to LOADING
  └─ "Accept & Create Draft" → POST /materializePageAction
                                ├─ JCR page tree creation
                                ├─ Unsplash image fetch (per image field)
                                └─ session.save() — atomic commit
        │
        ▼
[ Dialog: SUCCESS step ]
  Path to draft page shown
  Author closes dialog and reviews page in jContent
```

---

## Project Structure

```
src/
├── main/
│   ├── java/org/jahia/se/modules/ailandingpagegenerator/
│   │   ├── actions/
│   │   │   ├── GeneratePageAction.java       # Calls AI, returns JSON structure
│   │   │   ├── MaterializePageAction.java    # Creates JCR draft page tree
│   │   │   ├── GetConfigAction.java          # Returns audiences + tones
│   │   │   └── GetTemplatesAction.java       # Returns available page templates
│   │   ├── ingestion/
│   │   │   ├── DocumentIngestionService.java # base-64 doc → plain text
│   │   │   └── UrlFetchService.java          # Server-side URL fetch (SSRF-hardened)
│   │   ├── observability/
│   │   │   └── RequestLogger.java            # Structured logging, no content leakage
│   │   ├── prompt/
│   │   │   └── PromptAssembler.java          # Assembles + sandboxes the final prompt
│   │   ├── security/
│   │   │   ├── RateLimiter.java              # Per-user per-minute token bucket
│   │   │   └── RateLimitExceededException.java
│   │   └── service/
│   │       ├── AiLandingPageService.java     # Service interface
│   │       ├── AiLandingPageServiceImpl.java # OSGi component + ManagedService
│   │       ├── UnsplashService.java          # Image fetch interface
│   │       └── UnsplashServiceImpl.java      # Unsplash API v1 implementation
│   └── resources/
│       └── META-INF/
│           └── configurations/
│               └── org.jahia.se.modules.ailandingpagegenerator.cfg
└── javascript/
    ├── AiLandingPage.register.js             # Registers jContent action
    ├── AiLandingPage/
    │   ├── AiLandingPageAction.jsx           # Action button (permission check)
    │   ├── AiLandingPageDialog.jsx           # 4-step dialog
    │   ├── AiLandingPageDialogManager.jsx    # Singleton dialog opener
    │   ├── AiLandingPageDialog.scss          # Dialog styles
    │   ├── AiLandingPage.utils.jsx           # Fetch helpers
    │   ├── jcontent.constants.js             # Action name constants
    │   └── index.js
    └── AdminPanel/
        ├── AdminPanel.jsx                    # jContent admin panel panel
        ├── AdminPanel.constants.jsx
        └── AdminPanel.routes.jsx
```

---

## Security Considerations

| Threat | Mitigation |
|---|---|
| **Prompt injection** from author input or fetched URLs | Untrusted content wrapped in `=== BEGIN/END UNTRUSTED … ===` delimiters; model instructed to treat those blocks as data only |
| **SSRF via URL ingestion** | Host allow-list validated after URI parsing; empty list disables URL fetching entirely; HTTPS enforced |
| **API key exposure** | Key lives server-side only in the OSGi `.cfg` file; never sent to the browser |
| **Excessive AI cost** | Per-user rate limiting (configurable); input token cap with automatic truncation; output token cap |
| **Large document / URL payloads** | Document capped at 10 MB; URL response capped at `AI_URL_FETCH_MAX_SIZE_BYTES` |
| **Partial JCR writes on failure** | Single JCR session commit — exception discards the entire in-progress page tree |
| **Unauthorised access** | All actions require `jcr:write_default` + authenticated user; permission re-checked inside `MaterializePageAction` before any write |
| **Unsplash image size** | Binary download capped at 15 MB per image |

> **Important**: Rotate the `AI_API_KEY` and `UNSPLASH_ACCESS_KEY` values before committing to any shared repository. The keys present in the `.cfg` file in this repository are for local development only.

---

## License

MIT
