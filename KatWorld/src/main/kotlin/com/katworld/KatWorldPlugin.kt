package com.katworld

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

@CloudstreamPlugin
class KatWorldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PikaHDProvider())
        registerMainAPI(KatMovieHDProvider())
        registerMainAPI(MoviesBabaProvider())
        registerMainAPI(KatDramaProvider())
        registerMainAPI(KatMovie4KProvider())
    }
}

object KatWorldPortal {
    private const val PORTAL_URL = "https://katworld.net"
    private val cachedLinks = ConcurrentHashMap<String, String>()

    val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

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

                extractExternalOrigin(buttonUrl)?.let { origin ->
                    cachedLinks[siteKey] = origin
                    return origin
                }

                val resp = app.get(buttonUrl, headers = headers, allowRedirects = true)
                extractExternalOrigin(resp.url)?.let { origin ->
                    cachedLinks[siteKey] = origin
                    return origin
                }

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

abstract class KatWorldBaseProvider(
    private val siteKey: String,
    override var name: String,
    override var mainUrl: String,
    private val defaultTvType: TvType = TvType.Movie
) : MainAPI() {

    override val hasMainPage = true
    override var lang = "hi"

    override val mainPage = mainPageOf(
        "" to "Latest Releases"
    )

    private suspend fun activeUrl(): String {
        mainUrl = KatWorldPortal.getDomain(siteKey).removeSuffix("/")
        return mainUrl
    }

    private fun resolveUrl(url: String, base: String): String {
        return try {
            URI(base).resolve(url.trim()).toString()
        } catch (_: Exception) {
            url.trim()
        }
    }

    private suspend fun fetchDocumentWithBypass(targetUrl: String): Pair<String, Document> {
        val resp = app.get(targetUrl, headers = KatWorldPortal.headers, allowRedirects = true)
        val doc = resp.document

        if (doc.select("a[href]").size <= 3) {
            val html = resp.text
            val redirectRegex = Regex(
                """(?:url=|location(?:\.href)?\s*=\s*|window\.location\.replace\()['"\s]*(https?://[^'";\s>)]+)""",
                RegexOption.IGNORE_CASE
            )
            val nextUrl = redirectRegex.find(html)?.groupValues?.getOrNull(1)
                ?: doc.selectFirst("a[href^=http]")?.attr("href")

            if (!nextUrl.isNullOrBlank() && !nextUrl.equals(targetUrl, ignoreCase = true)) {
                val secondResp = app.get(nextUrl, headers = KatWorldPortal.headers, allowRedirects = true)
                if (secondResp.document.select("a[href]").size > doc.select("a[href]").size) {
                    return secondResp.url to secondResp.document
                }
            }
        }

        return resp.url to doc
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = activeUrl()
        val path = if (request.data.isEmpty()) "" else "${request.data.trim('/')}/"
        val url = if (page == 1) "$base/$path" else "$base/${path}page/$page/"

        val (finalUrl, document) = fetchDocumentWithBypass(url)
        val finalBase = runCatching {
            val uri = URI(finalUrl)
            "https://${uri.host}"
        }.getOrDefault(base)
        mainUrl = finalBase

        val items = parsePosts(document, finalBase).toMutableList()

        if (items.isEmpty() && page == 1) {
            val allAnchors = document.select("a").joinToString("\n") { a ->
                "text='${a.text()}' -> href='${a.attr("href")}'"
            }
            val debugInfo = "Requested: $url\nFinal URL: $finalUrl\nTitle: ${document.title()}\nLinks count: ${document.select("a").size}\n\nAnchors:\n$allAnchors\n\nHTML Snippet:\n${document.body().html().take(800)}"
            val encodedDebug = URLEncoder.encode(debugInfo, "UTF-8")
            items.add(
                newMovieSearchResponse(
                    "[Debug Tap Me] Links:${document.select("a").size}",
                    "$mainUrl/?debug_info=$encodedDebug",
                    defaultTvType
                )
            )
        }

        return newHomePageResponse(request.name, items)
    }

    private fun parsePosts(document: Document, base: String): List<SearchResponse> {
        val cards = document.select(
            "article, .post, .post-item, .blog-post, .result-item, ul.recent-posts li, .grid-item, .movie-item, .item"
        )
        val fromCards = cards.mapNotNull { it.toSearchResult(base) }.distinctBy { it.url }
        if (fromCards.isNotEmpty()) return fromCards

        val ignoredPaths = listOf(
            "/page/", "/category/", "/tag/", "/wp-", "/author/", "?s=", "#",
            "telegram", "instagram", "facebook", "twitter", "how-to-download", "dmca", "contact"
        )
        val groupedLinks = document.select("a[href]")
            .mapNotNull { a ->
                val rawHref = a.attr("href").trim()
                if (rawHref.isEmpty() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) return@mapNotNull null
                val href = resolveUrl(rawHref, base).removeSuffix("/")
                if (href == base.removeSuffix("/") || href.contains("katworld.net")) return@mapNotNull null
                if (ignoredPaths.any { href.contains(it, ignoreCase = true) }) return@mapNotNull null
                href to a
            }
            .groupBy({ it.first }, { it.second })

        return groupedLinks.mapNotNull { (href, aTags) ->
            val rawTitle = aTags.map { it.text().trim() }
                .firstOrNull { it.length > 8 }
                ?: aTags.firstNotNullOfOrNull { it.attr("title").trim().takeIf { t -> t.length > 8 } }
                ?: return@mapNotNull null

            val cleanTitle = cleanPostTitle(rawTitle)
            val posterUrl = aTags.firstNotNullOfOrNull { a ->
                a.selectFirst("img")?.extractImgSrc(base)
                    ?: a.parent()?.selectFirst("img")?.extractImgSrc(base)
                    ?: a.parent()?.parent()?.selectFirst("img")?.extractImgSrc(base)
            }

            newMovieSearchResponse(cleanTitle, href, defaultTvType) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(rawTitle)
            }
        }
    }

    private fun Element.toSearchResult(base: String): SearchResponse? {
        val titleElement = this.selectFirst(
            "h1 a, h2 a, h3 a, h4 a, .entry-title a, .post-title a, .title a, a[title]"
        ) ?: this.select("a[href]").firstOrNull { it.text().trim().length > 8 }
        ?: return null

        val rawTitle = titleElement.text().trim().ifEmpty { titleElement.attr("title").trim() }
        if (rawTitle.length < 4) return null

        val href = resolveUrl(titleElement.attr("href"), base)
        if (href.contains("katworld.net")) return null

        val cleanTitle = cleanPostTitle(rawTitle)
        val posterUrl = this.selectFirst("img")?.extractImgSrc(base)

        return newMovieSearchResponse(cleanTitle, href, defaultTvType) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(rawTitle)
        }
    }

    private fun Element.extractImgSrc(base: String): String? {
        val src = this.attr("data-src")
            .ifEmpty { this.attr("data-lazy-src") }
            .ifEmpty { this.attr("data-original") }
            .ifEmpty { this.attr("src") }
            .trim()
        if (src.isEmpty() || src.startsWith("data:")) return null
        return resolveUrl(src, base)
    }

    private fun cleanPostTitle(rawTitle: String): String {
        return rawTitle
            .substringBefore("[")
            .substringBefore("WEB-DL")
            .substringBefore("BluRay")
            .substringBefore("1080p")
            .substringBefore("720p")
            .trim()
            .ifEmpty { rawTitle }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = activeUrl()
        val (_, doc) = fetchDocumentWithBypass("$base/?s=$query")
        return parsePosts(doc, base)
    }

    override suspend fun load(url: String): LoadResponse? {
        if (url.contains("?debug_info=")) {
            val decoded = URLDecoder.decode(url.substringAfter("?debug_info="), "UTF-8")
            return newMovieLoadResponse("Debug Diagnostic Info", url, defaultTvType, "") {
                this.plot = decoded
            }
        }

        val (_, document) = fetchDocumentWithBypass(url)
        val rawTitle = document.selectFirst("h1.entry-title, h1.post-title, h1, h2.entry-title")?.text()?.trim()
            ?: document.title()
        val title = cleanPostTitle(rawTitle)

        val poster = document.selectFirst("div.entry-content img, .post-thumbnail img, article img")
            ?.extractImgSrc(mainUrl)

        val description = document.select("div.entry-content p, .post-content p")
            .firstOrNull { it.text().length > 60 }?.text()?.trim()

        val content = document.selectFirst("div.entry-content, div.post-content, article")
        val episodesMap = mutableMapOf<Int, MutableList<String>>()
        val directLinks = mutableListOf<String>()

        val epRegex = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE)

        content?.select("a[href]")?.forEach { aTag ->
            val link = aTag.attr("href").trim()
            if (link.isBlank() || link.startsWith("#") || link.contains(mainUrl)) return@forEach

            val contextText = "${aTag.parent()?.previousElementSibling()?.text() ?: ""} ${aTag.parent()?.text() ?: ""} ${aTag.text()}"
            val match = epRegex.find(contextText)

            if (match != null) {
                val epNum = match.groupValues[1].toIntOrNull() ?: 1
                episodesMap.getOrPut(epNum) { mutableListOf() }.add(link)
            } else {
                directLinks.add(link)
            }
        }

        return if (episodesMap.isNotEmpty()) {
            val episodes = episodesMap.entries.sortedBy { it.key }.map { (epNum, links) ->
                newEpisode(links.distinct().joinToString("|")) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, defaultTvType, directLinks.distinct().joinToString("|")) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = data.split("|").filter { it.isNotBlank() }
        for (link in links) {
            loadExtractor(link, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}

class KatMovieHDProvider : KatWorldBaseProvider(
    siteKey = "KatmovieHD",
    name = "KatmovieHD",
    mainUrl = "https://katmoviehd.rog",
    defaultTvType = TvType.Movie
) {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
}

class MoviesBabaProvider : KatWorldBaseProvider(
    siteKey = "MoviesBaba",
    name = "MoviesBaba",
    mainUrl = "https://moviesbaba.cam",
    defaultTvType = TvType.Movie
) {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
}

class PikaHDProvider : KatWorldBaseProvider(
    siteKey = "PikaHD",
    name = "PikaHD",
    mainUrl = "https://new.pikahd.co",
    defaultTvType = TvType.Anime
) {
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.TvSeries, TvType.Movie)
}

class KatDramaProvider : KatWorldBaseProvider(
    siteKey = "KatDrama",
    name = "KatDrama",
    mainUrl = "https://katdrama.com",
    defaultTvType = TvType.AsianDrama
) {
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.TvSeries, TvType.Movie)
}

class KatMovie4KProvider : KatWorldBaseProvider(
    siteKey = "Katmovie4K",
    name = "Katmovie4K",
    mainUrl = "https://katmovie4k.com",
    defaultTvType = TvType.Movie
) {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
}
