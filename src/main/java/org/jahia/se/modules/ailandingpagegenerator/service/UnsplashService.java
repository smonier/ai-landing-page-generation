package org.jahia.se.modules.ailandingpagegenerator.service;

/**
 * Fetches images from the Unsplash API server-side.
 *
 * The Unsplash access key is kept in the OSGi configuration and is never
 * exposed to the browser.  Each call performs two HTTP requests:
 *  1. Search — find the most relevant photo for the query.
 *  2. Download trigger — notify Unsplash of the download (required by their API
 *     guidelines for proper attribution tracking).
 * The image binary is then fetched and returned to the caller.
 */
public interface UnsplashService {

    /**
     * Search Unsplash for a landscape photo matching {@code query}, download it,
     * and return the binary data together with its metadata.
     *
     * @param query  free-text search query (e.g. the component's alt-text)
     * @return image data, or {@code null} if the service is not configured,
     *         the search yields no results, or any network error occurs
     */
    ImageData fetchImage(String query);

    /**
     * Immutable result returned by {@link #fetchImage}.
     *
     * @param bytes        raw image bytes
     * @param mimeType     MIME type (e.g. {@code image/jpeg})
     * @param fileName     suggested file name including extension
     * @param photographer display name of the Unsplash photographer (for attribution)
     */
    record ImageData(byte[] bytes, String mimeType, String fileName, String photographer) {}
}
