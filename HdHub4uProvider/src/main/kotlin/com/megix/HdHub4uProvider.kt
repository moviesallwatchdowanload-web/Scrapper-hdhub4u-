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

    private val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/category/bollywood-movies/page/%d/" to "Bollywood Movies",
        "$mainUrl/category/hollywood-movies/page/%d/" to "Hollywood Movies",
        "$mainUrl/category/hollywood-hindi-dubbed/page/%d/" to "Hollywood Hindi Dubbed",
        "$mainUrl/category/south-indian-movies/page/%d/" to "South Indian Movies",
        "$mainUrl/category/web-series/netflix/page/%d/" to "Netflix Series",
        "$mainUrl/category/web-series/disney-plus-hotstar/page/%d/" to "Disney+ Series",
        "$mainUrl/category/web-series/amazon-prime-video/page/%d/" to "Amazon Prime Series",
        "$mainUrl/category/web-series/mx-original/page/%d/" to "MX Original Series",
        "$mainUrl/category/anime-series/page/%d/" to "Anime Series",
        "$mainUrl/category/korean-series/page/%d/" to "Korean Series",
        "$mainUrl/category/pakistan-punjabi-movies/page/%d/" to "Punjabi Movies",
        "$mainUrl/category/4k-movies/page/%d/" to "4K Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.format(page)
        val doc = app.get(url, headers = mapOf("User-Agent" to ua)).document
        val list = doc.select("li.thumb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a") ?: return null
        val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return null
        val img = selectFirst("img") ?: return null

        var rawTitle = img.attr("alt").trim()
        if (rawTitle.isBlank()) rawTitle = img.attr("title").trim()
        if (rawTitle.isBlank()) rawTitle = anchor.text().trim()
        if (rawTitle.isBlank()) return null

        // Clean title: remove year onwards, tags
        var title = rawTitle
            .replace(Regex("""\s*\(\d{4}\).*$"""), "")
            .replace(Regex("""\s*(WEB-DL|HQ-HDTC|HDTC|DS4K|UNCUT|V2|iMAX).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|.*$"""), "")
            .replace(Regex("""\s+Download\s+"""), " ")
            .replace("Download ", "")
            .trim()
        if (title.isBlank()) title = rawTitle

        var poster = img.attr("src")
        if (poster.isBlank() || !poster.startsWith("http")) poster = img.attr("data-src")

        val fullHref = if (href.startsWith("/")) "$mainUrl$href" else href
        val isSeries = rawTitle.contains(Regex("""(?i)(season|series)"""))

        return newMovieSearchResponse(title, fullHref, if (isSeries) TvType.TvSeries else TvType.Movie) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val enc = java.net.URLEncoder.encode(query, "UTF-8")
        // Actual search endpoint: /search.html?q=query
        val url = if (page == 1) "$mainUrl/search.html?q=$enc" else "$mainUrl/search.html?q=$enc&page=$page"
        val doc = app.get(url, headers = mapOf("User-Agent" to ua)).document
        val results = doc.select("li.thumb").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = results.isNotEmpty())
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to ua)).document

        val rawTitle = doc.selectFirst("h1.page-title, h1.entry-title, h1")?.text()?.trim() ?: return null
        val title = rawTitle
            .replace(Regex("""\s*Download\s*"""), " ")
            .replace(Regex("""\s*(WEB-DL|HQ-HDTC|HDTC).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\|.*$"""), "")
            .trim()
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?: doc.selectFirst("div.entry-content img, article img")?.attr("src")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("div.entry-content p")?.text()?.trim()
        val year = Regex("""(19|20)\d{2}""").find(rawTitle)?.value?.toIntOrNull()

        val imdbUrl = doc.selectFirst("a[href*=imdb.com/title/]")?.attr("href") ?: ""
        val genres = doc.select("a[href*=category]").map { it.text() }.filter { it.length < 30 }.distinct()

        // Cast extraction
        val cast = mutableListOf<String>()
        doc.selectFirst("strong:contains(Stars)")?.let { starStrong ->
            val parentText = starStrong.parent()?.text() ?: ""
            val starsPart = parentText.substringAfter("Stars:", "").substringBefore("Director").trim()
            if (starsPart.isNotBlank()) {
                starsPart.split(",").forEach { cast.add(it.trim()) }
            }
        }

        // Detect series: title has "season" or url contains "series" or episodes present
        val isSeries = rawTitle.contains(Regex("""(?i)(season|series)""")) || url.contains("/series")
                || doc.select("a[href*=hubstream.art/#]").size > 3

        if (isSeries) {
            // Episodes in hubstream.art/#xxx links
            val epLinks = doc.select("a[href*=hubstream.art/#]")
                .mapNotNull { a ->
                    val epUrl = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val epTitle = a.text().trim().ifBlank { "Episode" }
                    epUrl to epTitle
                }
                .distinctBy { it.first }

            if (epLinks.isNotEmpty()) {
                val episodes = epLinks.mapIndexed { idx, (epUrl, epTitle) ->
                    val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: (idx + 1)
                    newEpisode(EpisodeLink(epUrl, epTitle)) {
                        this.name = epTitle
                        this.episode = epNum
                    }
                }
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = genres
                    if (imdbUrl.isNotBlank()) addImdbUrl(imdbUrl)
                    if (cast.isNotEmpty()) addActors(cast)
                }
            }
        }

        // Movie — find hdstream4u link
        val movieLinks = doc.select("a[href*=hdstream4u], a[href*=hubstream.art/#]")
            .mapNotNull { a ->
                val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = a.text().trim().ifBlank { "Server" }
                EpisodeLink(href, name)
            }
            .distinctBy { it.source }

        val data = if (movieLinks.isEmpty()) listOf(EpisodeLink(url, "Server")) else movieLinks

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            if (imdbUrl.isNotBlank()) addImdbUrl(imdbUrl)
            if (cast.isNotEmpty()) addActors(cast)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val sources = try {
                com.lagradost.cloudstream3.utils.AppUtils.parseJson<ArrayList<EpisodeLink>>(data)
            } catch (e: Exception) {
                listOf(EpisodeLink(data, "Server"))
            }

            sources.amap { link ->
                val url = link.source
                val label = link.name

                try {
                    // Try 1: direct loadExtractor
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e("HDHub4u", "loadExtractor failed: ${e.message}")
                }

                // Try 2: for hdstream4u / hubstream — fetch page and extract m3u8
                if (url.contains("hdstream4u", true) || url.contains("hubstream", true)) {
                    try {
                        val headers = mapOf("User-Agent" to ua, "Referer" to url)
                        val doc = app.get(url, headers = headers).document

                        // Direct iframe
                        doc.selectFirst("iframe[src]")?.attr("src")?.let { iframeSrc ->
                            if (iframeSrc.isNotBlank() && !iframeSrc.startsWith("about:")) {
                                loadExtractor(iframeSrc, url, subtitleCallback, callback)
                            }
                        }

                        // Packed JS decode
                        val html = doc.html()
                        val evalIdx = html.indexOf("eval(function(p,a,c,k,e,d)")
                        if (evalIdx >= 0) {
                            val endIdx = html.indexOf("'.split('|')))", evalIdx)
                            if (endIdx > evalIdx) {
                                val packed = html.substring(evalIdx, endIdx + 20)
                                decodePackedUrl(packed)?.let { m3u8 ->
                                    callback.invoke(
                                        newExtractorLink("HDHub4u", "HDHub4u - $label", m3u8, ExtractorLinkType.M3U8) {
                                            this.referer = url
                                            this.quality = Qualities.P1080.value
                                        }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("HDHub4u", "hdstream4u failed: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("HDHub4u", "loadLinks error: ${e.message}")
        }
        return true
    }

    private fun decodePackedUrl(packed: String): String? {
        return try {
            val regex = Regex("""eval\(function\(p,a,c,k,e,d\)\{.*?\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)\)\)""", RegexOption.DOT_MATCHES_ALL)
            val m = regex.find(packed) ?: return null
            val payload = m.groupValues[1]
            val base = m.groupValues[2].toInt()
            val words = m.groupValues[4].split("|")

            fun unbase(n: Int, b: Int): String {
                val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
                if (n < b) return chars[n].toString()
                return unbase(n / b, b) + chars[n % b]
            }

            val decoded = Regex("""\b\d+\b""").replace(payload) { mr ->
                val c = mr.value.toInt()
                if (c < words.size && words[c].isNotEmpty()) words[c] else unbase(c, base)
            }

            val nMatch = Regex("""n\s*=\s*(\{[^}]*\})""").find(decoded) ?: return null
            val nBlock = nMatch.groupValues[1]
            var url = Regex(""""1d"\s*:\s*"([^"]+)"""").find(nBlock)?.groupValues?.get(1) ?: return null

            val tokens = Regex("""\b([a-z0-9]{1,4})\b""").findAll(nBlock).map { it.value }.toSet()
            for (t in tokens) {
                try {
                    val idx = t.toInt(36)
                    if (idx < words.size && words[idx].isNotEmpty()) {
                        url = Regex("""\b$t\b""").replace(url, words[idx])
                    }
                } catch (e: Exception) {}
            }

            if (url.contains("m3u8")) url else null
        } catch (e: Exception) { null }
    }

    data class EpisodeLink(val source: String, val name: String = "Server")
}
