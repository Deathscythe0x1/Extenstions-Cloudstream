package com.katworld

import com.lagradost.cloudstream3.app

object KatWorldPortal {
    private const val PORTAL_URL = "https://katworld.net/"

    @Volatile
    private var cachedLinks: Map<String, String> = emptyMap()

    private val fallbackDomains = mapOf(
        "KatmovieHD" to "https://katmoviehd.rodeo",
        "MoviesBaba" to "https://moviesbaba.render",
        "PikaHD" to "https://new.pikahd.co",
        "KatDrama" to "https://katdrama.com",
        "Katmovie18" to "https://katmovie18.com",
        "Katmovie4K" to "https://katmovie4k.com"
    )

    suspend fun getDomain(siteKey: String): String {
        if (cachedLinks.isEmpty()) {
            try {
                val doc = app.get(PORTAL_URL).document
                val map = mutableMapOf<String, String>()

                doc.select("a[href]").forEach { a ->
                    val text = a.text().trim()
                    val href = a.attr("href").trim().removeSuffix("/")
                    if (href.startsWith("http")) {
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
                if (map.isNotEmpty()) {
                    cachedLinks = map
                }
            } catch (_: Exception) {
            }
        }
        return cachedLinks[siteKey] ?: fallbackDomains[siteKey] ?: PORTAL_URL
    }
}
