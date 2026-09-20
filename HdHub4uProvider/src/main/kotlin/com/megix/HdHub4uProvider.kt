package com.megix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.api.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URI

open class HdHub4uProvider : MainAPI() {
    override var mainUrl = "https://new6.hdhub4u.cl"
    override var name = "HDHub4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/category/bollywood-movies/" to "Bollywood Movies",
        "$mainUrl/category/hollywood-movies/" to "Hollywood Movies",
        "$mainUrl/category/hollywood-hindi-dubbed/" to "Hollywood Hindi Dubbed",
        "$mainUrl/category/south-indian-movies/" to "South Indian Movies",
        "$mainUrl/category/web-series/netflix/" to "Netflix Series",
        "$mainUrl/category/web-series/disney-plus-hotstar/" to "Disney+ Series",
        "$mainUrl/category/web-series/amazon-prime-video/" to "Amazon Prime Series",
        "$mainUrl/category/web-series/mx-original/" to "MX Original Series",
        "$mainUrl/category/anime-series/" to "Anime Series",
        "$mainUrl/category/korean-series/" to "Korean Series",
        "$mainUrl/category/pakistan-punjabi-movies/" to "Punjabi Movies",
        "$mainUrl/category/4k-movies/" to "4K Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")).document

        val results = mutableListOf<SearchResponse>()

        // Try multiple selectors — Dooplay/Psytv theme
        val selectors = listOf(
            "ul.recent-movies li.thumb",
            "article.post",
            "div.movies-grid > a",
            "div.post-item",
            "article",
            "li.thumb",
            "div.thumb"
        )

        for (sel in selectors) {
            val els = doc.select(sel)
            if (els.isNotEmpty()) {
                Log.d("HDHub4u", "Selector matched: $sel (${els.size} items)")
                els.forEach { el ->
                    el.toSearchResult()?.let { results.add(it) }
                }
                if (results.isNotEmpty()) break
            }
        }

        return newHomePageResponse(request.name, results.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = if (this.tagName() == "a") this else selectFirst("a")
        val href = anchor?.attr("href") ?: return null
        if (href.isBlank()) return null

        val img = selectFirst("img")
        var title = img?.attr("alt")?.trim()?.ifBlank { null }
            ?: img?.attr("title")?.trim()?.ifBlank { null }
            ?: anchor.text().trim()
        if (title.isBlank()) return null

        title = title.replace("Download ", "").trim()

        var poster = img?.attr("src") ?: ""
        if (poster.isEmpty() || !poster.startsWith("http")) {
            poster = img?.attr("data-src") ?: ""
        }

        val fullHref = if (href.startsWith("/")) "$mainUrl$href" else href

        val isSeries = title.contains(Regex("(?i)(season\\s*\\d|S\\d{1,2}\\s|complete|web.?series)"))
        val type = if (isSeries) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, fullHref, type) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/page/$page/?s=$enc"
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)")).document

        val results = mutableListOf<SearchResponse>()
        val selectors = listOf(
            "ul.recent-movies li.thumb",
            "article.post",
            "article",
            "li.thumb"
        )

        for (sel in selectors) {
            val els = doc.select(sel)
            if (els.isNotEmpty()) {
                els.forEach { el -> el.toSearchResult()?.let { results.add(it) } }
                if (results.isNotEmpty()) break
            }
        }

        return newSearchResponseList(results.distinctBy { it.url }, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14)")).document

        val title = doc.selectFirst("h1.page-title, h1.entry-title, h1")?.text()?.trim()?.replace("Download ", "") ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.post-thumbnail figure img, .entry-content img")?.attr("src")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("div.entry-content p")?.text()?.trim()
        val year = Regex("""(19|20)\d{2}""").find(title)?.value?.toIntOrNull()
        val genres = doc.select("div.entry-content a[href*=category]").map { it.text() }.distinct()
        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title/]")?.attr("href") ?: ""

        val isSeries = title.contains(Regex("(?i)(season|series|episode)"))

        if (isSeries) {
            // Series — extract episodes
            val epLinks = doc.select("a[href*=episode], a[href*=season], div.entry-content a[href*=hdhub4u]")
                .filter { a ->
                    val t = a.text().trim()
                    t.contains(Regex("(?i)(episode|EP|S\\d|480|720|1080|4K|Download)"))
                }

            if (epLinks.isNotEmpty()) {
                val episodes = epLinks.mapIndexedNotNull { idx, a ->
                    val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                    val epTitle = a.text().trim().ifBlank { "Episode ${idx + 1}" }
                    val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                    newEpisode(epUrl) {
                        this.name = epTitle
                        this.episode = epNum
                    }
                }
                if (episodes.isNotEmpty()) {
                    return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = poster
                        this.plot = plot
                        this.year = year
                        this.tags = genres
                        if (imdbUrl.isNotBlank()) addImdbUrl(imdbUrl)
                    }
                }
            }
        }

        // Movie — find hdstream4u link
        val streamLink = doc.selectFirst("a[href*=hdstream4u], a[href*=hubstream], a[href*=hubcloud], a[href*=gdflix]")
            ?.attr("href")

        val data = EpisodeLink(streamLink ?: url)
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            if (imdbUrl.isNotBlank()) addImdbUrl(imdbUrl)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Try parse as EpisodeLink list, else raw URL
            val sources = try {
                com.lagradost.cloudstream3.utils.AppUtils.parseJson<ArrayList<EpisodeLink>>(data)
            } catch (e: Exception) {
                listOf(EpisodeLink(data))
            }

            sources.amap { link ->
                val url = link.source
                // First try direct extractor
                loadExtractor(url, mainUrl, subtitleCallback, callback)

                // Then try hdstream4u page → m3u8 extraction
                if (url.contains("hdstream4u") || url.contains("hubstream")) {
                    try {
                        val pageDoc = app.get(url, headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 14)",
                            "Cookie" to "toronites_server=vidstream"
                        )).document

                        // Find iframe
                        val iframe = pageDoc.selectFirst("iframe[src]")?.attr("src")
                        if (!iframe.isNullOrBlank()) {
                            loadExtractor(iframe, url, subtitleCallback, callback)
                        }
                    } catch (e: Exception) {
                        Log.e("HDHub4u", "loadLinks failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDHub4u", "loadLinks error: ${e.message}")
        }
        return true
    }

    data class EpisodeLink(val source: String)
}
