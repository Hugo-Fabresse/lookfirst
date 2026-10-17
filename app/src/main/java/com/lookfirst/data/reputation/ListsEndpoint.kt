package com.lookfirst.data.reputation

import java.net.URI

private val TAG_PATTERN = Regex("^[0-9]{1,10}$")
private const val HTTPS_PORT = 443

/**
 * Where the reputation bundle is published (design document, section 6): a Codeberg repository, release assets.
 * Compiled into the app and never read from server data or settings. [host] is the only host the downloader talks
 * to; [basePath] is `/<owner>/<repository>/releases/download`.
 *
 * The owner and repository are PROVISIONAL until LF-25 creates the real repository and confirms them.
 */
class ListsEndpoint(val host: String = HOST, val basePath: String = BASE_PATH) {
    /** `<base>/latest/<file>`: the fixed tag that holds only the manifest and its signature. */
    internal fun latestUrl(file: String): URI = URI("https", host, "$basePath/latest/$file", null)

    /** `<base>/<tag>/<file>`, or null if [tag] is not a plain decimal number of 1 to 10 digits. */
    internal fun bundleUrl(tag: String, file: String): URI? =
        if (TAG_PATTERN.matches(tag)) URI("https", host, "$basePath/$tag/$file", null) else null

    /** True for an `https` address on [host], default port, no user information: the only redirects followed. */
    internal fun isAllowedTarget(uri: URI): Boolean = uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals(host, ignoreCase = true) &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port == HTTPS_PORT)

    companion object {
        const val HOST = "codeberg.org"

        // Provisional owner/repository (LF-25 confirms them).
        const val BASE_PATH = "/Hugo-Fabresse/lookfirst-lists/releases/download"
    }
}
