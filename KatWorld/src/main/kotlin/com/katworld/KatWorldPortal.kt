package com.katworld

import com.lagradost.cloudstream3.app
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object KatWorldPortal {
    private const val PORTAL_URL = "https://katworld.net"
    private val cachedLinks = ConcurrentHashMap<String, String>()
    private val buttonUrls = ConcurrentHashMap<String, String>()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$PORTAL_URL/"
    )

    private val fallbackDomains = mapOf(
        "KatmovieHD" to "https://katmoviehd.top",
        "MoviesBaba" to "https://moviesbaba.lol",
        "PikaHD" to "https://new.pikahd.co",
        "KatDrama" to "https://katdrama.my",
        "Katmovie18" to "https://katmovie18.my",
        "Katmovie4K" to "https://katmovie4k.com"
    )

    private fun resolveHref(rawHref: String): String {
        return try {
            URI(PORTAL_URL).resolve(rawHref.trim()).toString().removeSuffix("/")
        } catch (_: Exception) {
            rawHref.trim().removeSuffix("/")
        }
    }

    private fun extractOrigin(url: String): String? {
        return try {
            val uri = URI(url.trim())
            val host = uri.host ?: return null
            if (host.contains("katworld.net", ignoreCase = true)) return null
            "${uri.scheme ?: "https"}://$host"
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun loadPortalButtons() {
        if (buttonUrls.isNotEmpty()) return
        try {
            val doc = app.get(PORTAL_URL, headers = headers).document
            doc.select("a[href]").forEach { a ->
                val text = "${a.text()} ${a.attr("title")} ${a.parent()?.text() ?: ""}"
                val rawHref = a.attr("href").trim()
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) return@forEach

                val fullUrl = resolveHref(rawHref)
                when {
                    text.contains("KatmovieHD", ignoreCase = true) -> buttonUrls.putIfAbsent("KatmovieHD", fullUrl)
                    text.contains("MoviesBaba", ignoreCase = true) -> buttonUrls.putIfAbsent("MoviesBaba", fullUrl)
                    text.contains("PikaHD", ignoreCase = true) -> buttonUrls.putIfAbsent("PikaHD", fullUrl)
                    text.contains("KatDrama", ignoreCase = true) -> buttonUrls.putIfAbsent("KatDrama", fullUrl)
                    text.contains("Katmovie18", ignoreCase = true) -> buttonUrls.putIfAbsent("Katmovie18", fullUrl)
                    text.contains("Katmovie4K", ignoreCase = true) -> buttonUrls.putIfAbsent("Katmovie4K", fullUrl)
                }
            }
        } catch (_: Exception) {
        }
    }

    suspend fun getDomain(siteKey: String): String {
        if (siteKey == "PikaHD") return "https://new.pikahd.co"
        cachedLinks[siteKey]?.let { return it }

        loadPortalButtons()
        val targetUrl = buttonUrls[siteKey]

        if (targetUrl != null) {
            // 1. If the button already points directly to an external domain, use it immediately
            extractOrigin(targetUrl)?.let { origin ->
                cachedLinks[siteKey] = origin
                return origin
            }

            // 2. If the button points to a katworld.net redirect link, follow it to find the destination domain
            try {
                val response = app.get(targetUrl, headers = headers, allowRedirects = true)

                extractOrigin(response.url)?.let { origin ->
                    cachedLinks[siteKey] = origin
                    return origin
                }

                response.headers["Location"]?.let { loc ->
                    extractOrigin(loc)?.let { origin ->
                        cachedLinks[siteKey] = origin
                        return origin
                    }
                }

                val urlRegex = Regex("""https?://(?!katworld\.net)[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
                urlRegex.find(response.text)?.value?.let { matchedUrl ->
                    extractOrigin(matchedUrl)?.let { origin ->
                        cachedLinks[siteKey] = origin
                        return origin
                    }
                }
            } catch (_: Exception) {
            }
        }

        return fallbackDomains[siteKey] ?: "https://new.pikahd.co"
    }
}
