package com.katworld

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    )

    private suspend fun activeUrl(): String {
        mainUrl = KatWorldPortal.getDomain(siteKey).removeSuffix("/")
        return mainUrl
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val base = activeUrl()
        val path = if (request.data.isEmpty()) "" else "${request.data.trim('/')}/"
        val url = if (page == 1) "$base/$path" else "$base/${path}page/$page/"

        val response = app.get(url, headers = headers)
        val document = response.document
        val items = parsePosts(document, base).toMutableList()

        // Diagnostic card: If 0 items are parsed on page 1, display the HTTP status & URL so you can see why
        if (items.isEmpty() && page == 1) {
            items.add(
                newMovieSearchResponse(
                    "[Debug] HTTP ${response.code} | Links: ${document.select("a").size} | $base",
                    url,
                    defaultTvType
                )
            )
        }

        return newHomePageResponse(request.name, items)
    }

    private fun parsePosts(document: Document, base: String): List<SearchResponse> {
        // 1. Try standard WordPress post containers first
        val cards = document.select(
            "article, .post, .post-item, .blog-post, .result-item, ul.recent-posts li, .grid-item, .movie-item, .item"
        )
        val fromCards = cards.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        if (fromCards.isNotEmpty()) return fromCards

        // 2. Fallback: Group all internal post links by href (handles themes where image <a> and text <a> are siblings)
        val ignoredPaths = listOf("/page/", "/category/", "/tag/", "/wp-", "/author/", "?s=", "#")
        val groupedLinks = document.select("a[href]")
            .mapNotNull { a ->
                val href = fixUrl(a.attr("href")).removeSuffix("/")
                if (!href.startsWith(base) || href == base.removeSuffix("/")) return@mapNotNull null
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
                a.selectFirst("img")?.extractImgSrc()
                    ?: a.parent()?.selectFirst("img")?.extractImgSrc()
            }

            newMovieSearchResponse(cleanTitle, href, defaultTvType) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(rawTitle)
            }
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(
            "h1 a, h2 a, h3 a, h4 a, .entry-title a, .post-title a, .title a, a[title]"
        ) ?: this.select("a[href]").firstOrNull { it.text().trim().length > 8 }
        ?: return null

        val rawTitle = titleElement.text().trim().ifEmpty { titleElement.attr("title").trim() }
        if (rawTitle.isEmpty()) return null

        val href = fixUrl(titleElement.attr("href"))
        val cleanTitle = cleanPostTitle(rawTitle)
        val posterUrl = this.selectFirst("img")?.extractImgSrc()

        return newMovieSearchResponse(cleanTitle, href, defaultTvType) {
            this.posterUrl = posterUrl
            this.quality = getQualityFromString(rawTitle)
        }
    }

    private fun Element.extractImgSrc(): String? {
        val src = this.attr("data-src")
            .ifEmpty { this.attr("data-lazy-src") }
            .ifEmpty { this.attr("data-original") }
            .ifEmpty { this.attr("src") }
        return fixUrlNull(src)
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
        val document = app.get("$base/?s=$query", headers = headers).document
        return parsePosts(document, base)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document
        val rawTitle = document.selectFirst("h1.entry-title, h1.post-title, h1, h2.entry-title")?.text()?.trim()
            ?: document.title()
        val title = cleanPostTitle(rawTitle)

        val poster = document.selectFirst("div.entry-content img, .post-thumbnail img, article img")
            ?.extractImgSrc()

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

class PikaHDProvider : KatWorldBaseProvider(
    siteKey = "PikaHD",
    name = "PikaHD",
    mainUrl = "https://new.pikahd.co",
    defaultTvType = TvType.Anime
) {
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
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
