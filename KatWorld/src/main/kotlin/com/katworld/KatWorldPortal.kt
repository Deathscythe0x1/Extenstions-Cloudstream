package com.katworld

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl

object KatWorldPortal {
    private const val PORTAL_URL = "https://katworld.net"

    @Volatile
    private var cachedLinks: Map<String, String> = emptyMap()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    private val fallbackDomains = mapOf(
        "KatmovieHD" to "https://katmoviehd.rodeo",
        "MoviesBaba" to "https://moviesbaba.render",
        "PikaHD" to "https://new.pikahd.co",
        "KatDrama" to "https://katdrama.com",
        "Katmovie18" to "https://katmovie18.com",
        "Katmovie4K" to "https://katmovie4k.com"
    )

    suspend fun getDomain(siteKey: String): String {
        // PikaHD's direct domain is already known and stable
        if (siteKey == "PikaHD") return "https://new.pikahd.co"

        if (cachedLinks.isEmpty()) {
            try {
                val doc = app.get(PORTAL_URL, headers = headers).document
                val map = mutableMapOf<String, String>()

                doc.select("a[href]").forEach { a ->
                    val text = "${a.text()} ${a.attr("title")} ${a.parent()?.text() ?: ""}".trim()
                    val rawHref = a.attr("href").trim()
                    if (rawHref.isNotEmpty() && !rawHref.startsWith("#") && !rawHref.startsWith("javascript")) {
                        val href = fixUrl(rawHref, PORTAL_URL).removeSuffix("/")
                        if (!href.contains("katworld.net", ignoreCase = true)) {
                            when {
                                text.contains("KatmovieHD", ignoreCase = true) -> map["KatmovieHD"] = href
                                text.contains("MoviesBaba", ignoreCase = true) -> map["MoviesBaba"] = href
                                text.contains("PikaHD", ignoreCase = true) -> map["PikaHD"] = href
                                text.contains("KatDrama", ignoreCase = true) -> map["KatDrama"] = href
                                text.contains("Katmovie18", ignoreCase = true) -> map["Katmovie18"] = href
                                text.contains("Katmovie4K", ignoreCase = true) -> map["Katmovie4K"] = href
                            }
                        }
                    }
                }
                if (map.isNotEmpty()) {
                    cachedLinks = map
                }
            } catch (_: Exception) {
            }
        }
        return cachedLinks[siteKey] ?: fallbackDomains[siteKey] ?: "https://new.pikahd.co"
    }
}
