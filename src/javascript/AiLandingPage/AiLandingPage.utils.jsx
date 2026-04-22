/**
 * Utility helpers for calling the Jahia back-end actions.
 * All calls go to the module's own Java actions — never to the AI provider directly.
 */

/**
 * Build the Jahia action URL for the given node path, locale, and action name.
 */
export function buildActionUrl(siteKey, lang, nodePath, actionName) {
    return `/cms/render/default/${encodeURIComponent(lang)}${nodePath}.${actionName}.do`;
}

/**
 * Fetch the dialog configuration (audiences, tones) from the back-end.
 *
 * @param {string} nodePath - JCR path of the current page node
 * @param {string} lang     - current locale (e.g. "en")
 * @returns {Promise<{audiences: string[], tones: string[]}>}
 */
export async function getConfig(nodePath, lang) {
    const url = buildActionUrl(null, lang, nodePath, 'aiLandingPageGetConfig');
    try {
        const response = await fetch(url, {
            method: 'POST',
            headers: {'X-Requested-With': 'XMLHttpRequest'},
            credentials: 'same-origin'
        });
        const text = await response.text();
        if (!text || !response.ok) {
            return null;
        }

        const json = JSON.parse(text);
        return {
            audiences: (json.audiences || '').split(',').map(s => s.trim()).filter(Boolean),
            tones: (json.tones || '').split(',').map(s => s.trim()).filter(Boolean)
        };
    } catch (_) {
        return null;
    }
}

/**
 * Call the generatePageAction and return the response JSON.
 *
 * @param {object} params
 * @param {string} params.nodePath       - JCR path of the current page node
 * @param {string} params.lang           - current locale (e.g. "en")
 * @param {string} params.prompt         - author's natural-language prompt
 * @param {string} params.audience       - selected audience
 * @param {string} params.tone           - selected tone
 * @param {string|null} params.documentBase64   - base-64 encoded document, or null
 * @param {string|null} params.documentMimeType - MIME type, or null
 * @param {string|null} params.urls      - comma-separated URLs, or null
 * @returns {Promise<{success: boolean, structureJson: string, error: string|null}>}
 */
export async function generatePage({
    nodePath, lang, prompt, audience, tone,
    documentBase64, documentMimeType, urls
}) {
    const url = buildActionUrl(null, lang, nodePath, 'generatePageAction');
    const body = new URLSearchParams();
    body.set('prompt', prompt);
    body.set('audience', audience);
    body.set('tone', tone);
    if (documentBase64) {
        body.set('documentBase64', documentBase64);
        body.set('documentMimeType', documentMimeType || '');
    }

    if (urls) {
        body.set('urls', urls);
    }

    const response = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'X-Requested-With': 'XMLHttpRequest'
        },
        credentials: 'same-origin',
        body: body.toString()
    });

    const text = await response.text();
    // eslint-disable-next-line no-console
    console.debug('[generatePage] status=%d body=%s', response.status, text.substring(0, 500));
    if (!text) {
        return {success: false, error: 'Empty response from server. Please try again.'};
    }

    let json;
    try {
        json = JSON.parse(text);
    } catch (_) {
        return {success: false, error: 'Invalid response from server. Please try again.'};
    }

    if (response.status === 429) {
        return {success: false, rateLimited: true, error: json.error || 'Rate limit exceeded.'};
    }

    if (!response.ok) {
        return {success: false, error: json.error || 'Generation failed.'};
    }

    return json;
}

/**
 * Fetch the available page templates for the current site via the Content Editor
 * GraphQL forms API — the same source the Content Editor j:templateName dropdown uses.
 *
 * @param {string} nodePath - JCR path of the parent node (will be used as uuidOrPath)
 * @param {string} lang     - current locale (e.g. "en")
 * @returns {Promise<Array<{name: string, title: string}>>}
 */
export async function getTemplates(nodePath, lang) {
    const query = `
        query getPageTemplates($uuidOrPath: String!, $lang: String!) {
            forms {
                createForm(
                    primaryNodeType: "jnt:page"
                    uiLocale: $lang
                    locale: $lang
                    uuidOrPath: $uuidOrPath
                ) {
                    sections {
                        fieldSets {
                            fields {
                                name
                                valueConstraints {
                                    value { string }
                                    displayValue
                                }
                            }
                        }
                    }
                }
            }
        }
    `;

    try {
        const response = await fetch('/modules/graphql', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Requested-With': 'XMLHttpRequest'
            },
            credentials: 'same-origin',
            body: JSON.stringify({query, variables: {uuidOrPath: nodePath, lang}})
        });

        if (!response.ok) {
            return [];
        }

        const json = await response.json();
        const sections = json?.data?.forms?.createForm?.sections ?? [];
        for (const section of sections) {
            for (const fieldSet of (section.fieldSets ?? [])) {
                for (const field of (fieldSet.fields ?? [])) {
                    if (field.name === 'j:templateName' && Array.isArray(field.valueConstraints)) {
                        return field.valueConstraints.map(c => ({
                            name: c.value.string,
                            title: c.displayValue || c.value.string
                        }));
                    }
                }
            }
        }

        return [];
    } catch (_) {
        return [];
    }
}

/**
 * Call the materializePageAction to persist the confirmed page structure.
 *
 * @param {object} params
 * @param {string} params.nodePath     - JCR path of the parent page node
 * @param {string} params.lang         - current locale
 * @param {string} params.structureJson - JSON string from generatePage
 * @param {string} params.pageTitle    - title for the new page node
 * @param {string} params.templateName - j:templateName value for the new page
 * @returns {Promise<{success: boolean, nodePath: string|null, error: string|null}>}
 */
export async function materializePage({nodePath, lang, structureJson, pageTitle, templateName}) {
    const url = buildActionUrl(null, lang, nodePath, 'materializePageAction');
    const body = new URLSearchParams();
    body.set('structureJson', structureJson);
    body.set('pageTitle', pageTitle);
    body.set('templateName', templateName);

    const response = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'X-Requested-With': 'XMLHttpRequest'
        },
        credentials: 'same-origin',
        body: body.toString()
    });

    const text = await response.text();
    if (!text) {
        return {success: false, error: 'Empty response from server. Please try again.'};
    }

    let json;
    try {
        json = JSON.parse(text);
    } catch (_) {
        return {success: false, error: 'Invalid response from server. Please try again.'};
    }

    if (!response.ok) {
        return {success: false, error: json.error || 'Page creation failed.'};
    }

    return json;
}

/**
 * Read a file as base-64 encoded string.
 * @param {File} file
 * @returns {Promise<{base64: string, mimeType: string}>}
 */
export function fileToBase64(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => {
            // Result is "data:<mime>;base64,<data>"
            const [header, data] = reader.result.split(',');
            const mimeType = header.replace('data:', '').replace(';base64', '');
            resolve({base64: data, mimeType});
        };

        reader.onerror = reject;
        reader.readAsDataURL(file);
    });
}
