package com.katworld

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
    private const val PORTAL_URL = "https://katworld.net/"
    private var cachedLinks: Map<String, String> = emptyMap()

    private val fallbackDomains = mapOf(
        "KatmovieHD" to "https://katmoviehd.rodeo",
        "MoviesBaba" to "https://moviesbaba.render",
        "PikaHD" to "https://new.pikahd.co",
        "KatDrama" to "https://katdrama.com",
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
        mainUrl = KatWorldPortal.getDomain(siteKey)
        return mainUrl
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = activeUrl()
        val path = if (request.data.isEmpty()) "" else "${request.data.trim('/')}/"
        val url = if (page == 1) "$base/$path" else "$base/${path}page/$page/"

        val document = app.get(url).document
        val items = document.select("article, div.post-item, ul.recent-posts li").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h3.entry-title a, h2 a, .post-title a")
            ?: return null
        val rawTitle = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))

        val cleanTitle = rawTitle
            .substringBefore("[")
            .substringBefore("WEB-DL")
            .substringBefore("BluRay")
            .substringBefore("1080p")
            .trim()

        val posterUrl = this.selectFirst("img")?.let { img ->
            val src = img.attr("data-src").ifEmpty {
                img.attr("data-lazy-src").ifEmpty { img.attr("src") }
            }
            fixUrlNull(src)
        }

        return newMovieSearchResponse(cleanTitle, href, defaultTvType) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(rawTitle)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = activeUrl()
        val document = app.get("$base/?s=$query").document
        return document.select("article, div.post-item, div.result-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val rawTitle = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim()
            ?: return null
        val title = rawTitle.substringBefore("[").trim()

        val poster = document.selectFirst("div.entry-content img, .post-thumbnail img")?.let { img ->
            val src = img.attr("data-src").ifEmpty { img.attr("src") }
            fixUrlNull(src)
        }

        val description = document.select("div.entry-content p")
            .firstOrNull { it.text().length > 60 }?.text()?.trim()

        val content = document.selectFirst("div.entry-content, div.post-content")
        val episodesMap = mutableMapOf<Int, MutableList<String>>()
        val directLinks = mutableListOf<String>()

        val epRegex = Regex("""(?:Episode|Ep|E)\s*(\d+)""", RegexOption.IGNORE_CASE)

        content?.select("a[href]")?.forEach { aTag ->
            val link = aTag.attr("href")
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

class PikaHDProvider : KatWorldBaseProvider(
    siteKey = "PikaHD",
    name = "PikaHD",
    mainUrl = "https://new.pikahd.co",
    defaultTvType = TvType.Anime
) {
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
}

class KatMovieHDProvider : KatWorldBaseProvider(
    siteKey = "KatmovieHD",
    name = "KatmovieHD",
    mainUrl = "https://katmoviehd.rodeo",
    defaultTvType = TvType.Movie
) {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
}

class MoviesBabaProvider : KatWorldBaseProvider(
    siteKey = "MoviesBaba",
    name = "MoviesBaba",
    mainUrl = "https://moviesbaba.render",
    defaultTvType = TvType.Movie
) {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
}

class KatDramaProvider : KatWorldBaseProvider(
    siteKey = "KatDrama",
    name = "KatDrama",
    mainUrl = "https://katdrama.com",
    defaultTvType = TvType.AsianDrama
) {
    override val supportedTypes = setOf(TvType.AsianDrama)
}

class KatMovie4KProvider : KatWorldBaseProvider(
    siteKey = "Katmovie4K",
    name = "Katmovie4K",
    mainUrl = "https://katmovie4k.com",
    defaultTvType = TvType.Movie
) {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
}
