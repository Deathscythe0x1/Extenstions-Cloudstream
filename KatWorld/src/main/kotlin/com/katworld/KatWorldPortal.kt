package com.katworld

import com.lagradost.cloudstream3.app
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

object KatWorldPortal {
    private const val PORTAL_URL = "https://katworld.net"
    private val cachedLinks = ConcurrentHashMap<String, String>()

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Referer" to "$PORTAL_URL/"
    )

    // Updated live fallback domains
    private val fallbackDomains = mapOf(
        "KatmovieHD" to "https://katmoviehd.top",
        "MoviesBaba" to "https://moviesbaba.lol",
        "PikaHD" to "https://new.pikahd.co",
        "KatDrama" to "https://katdrama.my",
        "Katmovie18" to "https://katmovie18.my",
        "Katmovie4K" to "https://katmovie4k.com"
    )

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

    suspend fun getDomain(siteKey: String): String {
        if (siteKey == "PikaHD") return "https://new.pikahd.co"
        cachedLinks[siteKey]?.let { return it }

        try {
            // 1. Follow the portal's ?type= redirect endpoint
            val redirectEndpoint = "$PORTAL_URL/?type=$siteKey"
            val response = app.get(redirectEndpoint, headers = headers, allowRedirects = true)

            // Check if OkHttp followed an HTTP 301/302 redirect to the new domain
            extractOrigin(response.url)?.let { origin ->
                cachedLinks[siteKey] = origin
                return origin
            }

            // Check Location header if redirect wasn't automatically followed
            response.headers["Location"]?.let { loc ->
                extractOrigin(loc)?.let { origin ->
                    cachedLinks[siteKey] = origin
                    return origin
                }
            }

            // Check HTML body for <meta http-equiv="refresh"> or JS window.location redirects
            val html = response.text
            val urlRegex = Regex("""https?://(?!katworld\.net)[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")
            urlRegex.find(html)?.value?.let { matchedUrl ->
                extractOrigin(matchedUrl)?.let { origin ->
                    cachedLinks[siteKey] = origin
                    return origin
                }
            }
        } catch (_: Exception) {
        }

        // 2. Fallback to the current live domain map
        return fallbackDomains[siteKey] ?: "https://new.pikahd.co"
    }
}
