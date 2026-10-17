package com.lookfirst.data.reputation

import java.net.URI

private const val MIB = 1024L * 1024L

/**
 * One public list, fetched directly from its publisher (ADR-0008). The URL is compiled into the app: nothing the
 * network says can add a source or change an address. [maxDownloadBytes] is counted while streaming;
 * [minEntries] and [maxEntries] bound what is accepted after parsing.
 */
internal class ListSource(
    val id: Int,
    val displayName: String,
    val url: URI,
    val maxDownloadBytes: Long,
    val minEntries: Long,
    val maxEntries: Long,
) {
    val host: String get() = url.host
}

/** The sources the app knows. Adding one needs the criteria of `reputation-sources.md` and a new source id. */
internal object ListSources {
    /**
     * CERT Polska warning list: active malicious domains, one per line (about 2.5 MB, 125 000 entries).
     * `minEntries` is about a third of today's size: review it if the list grows a lot.
     */
    val CERT_PL = ListSource(
        id = SourceIds.CERT_PL,
        displayName = "CERT Polska",
        url = URI("https://hole.cert.pl/domains/v2/domains.txt"),
        maxDownloadBytes = 8 * MIB,
        minEntries = 40_000,
        maxEntries = 600_000,
    )

    val ALL: List<ListSource> = listOf(CERT_PL)
}
