package com.katworld

import com.lagradost.cloudstream3.app
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object KatWorldPortal {
    private const val PORTAL_URL = "https://katworld.net"
    private val cachedLinks = ConcurrentHashMap<String, String>()

    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // Verified live domains from katworld.net buttons
    private val fallbackDomains = mapOf(
        "KatmovieHD" to "https://katmoviehd.rog",
        "MoviesBaba" to "https://moviesbaba.cam",
        "PikaHD" to "https://new.pikahd.co",
        "KatDrama" to "https://katdrama.com",
        "Katmovie18" to "https://katmovie18.dev",
        "Katmovie4K" to "https://katmovie4k.com"
    )

    private fun extractExternalOrigin(url: String): String? {
        return try {
            val uri = URI(url.trim())
            val host = uri.host ?: return null
            if (host.contains("katworld.net", ignoreCase = true) ||
                host.contains("google", ignoreCase = true) ||
                host.contains("telegram", ignoreCase = true)
            ) return null
            "https://$host"
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getDomain(siteKey: String): String {
        if (siteKey == "PikaHD") return "https://new.pikahd.co"
        cachedLinks[siteKey]?.let { return it }

        try {
            val doc = app.get(PORTAL_URL, headers = headers).document

            for (a in doc.select("a[href]")) {
                val text = "${a.text()} ${a.attr("title")} ${a.parent()?.text() ?: ""}"
                if (!text.contains(siteKey, ignoreCase = true)) continue

                val rawHref = a.attr("href").trim()
                if (rawHref.isEmpty() || rawHref.startsWith("#")) continue

                val buttonUrl = try {
                    URI(PORTAL_URL).resolve(rawHref).toString()
                } catch (_: Exception) {
                    rawHref
                }

                // Case A: Button points directly to the domain
                extractExternalOrigin(buttonUrl)?.let { origin ->
                    cachedLinks[siteKey] = origin
                    return origin
                }

                // Case B: Button is a redirect link on katworld.net -> follow it
                val resp = app.get(buttonUrl, headers = headers, allowRedirects = true)
                extractExternalOrigin(resp.url)?.let { origin ->
                    cachedLinks[siteKey] = origin
                    return origin
                }

                // Case C: HTML/JS redirect inside the button response
                val regex = Regex("""https?://[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
                for (match in regex.findAll(resp.text)) {
                    extractExternalOrigin(match.value)?.let { origin ->
                        cachedLinks[siteKey] = origin
                        return origin
                    }
                }
            }
        } catch (_: Exception) {
        }

        return fallbackDomains[siteKey] ?: "https://new.pikahd.co"
    }
}
