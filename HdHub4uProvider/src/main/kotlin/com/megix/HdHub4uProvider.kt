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
    override var name = "HdHub4u"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    private val tmdbKeys = listOf(
        "bb4df3b8b43977bbfb1621cdfd5c5a85",
        "bb4df3b8b43977bbfb1621cdfd5c5a85"
    )
    private val tmdbImageBase = "https://image.tmdb.org/t/p/original"

    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama, TvType.Anime
    )

    init {
        runBlocking { basemainUrl?.let { mainUrl = it } }
    }

    companion object {
        val basemainUrl: String? by lazy {
            runBlocking {
                try {
                    val response = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
                    val json = response.text
                    val jsonObject = JSONObject(json)
                    jsonObject.optString("hdhub4u").ifEmpty { null }
                } catch (e: Exception) { null }
            }
        }
    }

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
        val document = app.get(request.data.format(page)).document
        val home = document.select("article a, .movies-grid > a, .post-item a").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.select("img").attr("alt").ifEmpty {
            this.attr("title").ifEmpty { this.text().trim() }
        }.replace("Download ", "").ifEmpty { return null }
        val href = this.attr("href").ifEmpty { return null }
        var posterUrl = this.select("img").attr("src")
        if (posterUrl.isEmpty() || !posterUrl.contains("http")) {
            posterUrl = this.select("img").attr("data-src")
        }
        val isSeries = title.contains(Regex("(?i)(season |S\\d{1,2}|episode|complete|web.?series)"))
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, URI(href).path, type) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = "$mainUrl/page/$page/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document
        val results = document.select("article, .post-item").mapNotNull { el ->
            el.selectFirst("a")?.toSearchResult()
        }.distinctBy { it.url }
        if (results.isEmpty()) return null
        return newSearchResponseList(results, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(fixUrl(url)).document
        var title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("title")?.text()
            ?: return null
        title = title.replace(Regex("(?i)download\\s*"), "").replace(Regex("\\s+\\|.*$"), "").trim()

        var posterUrl = document.selectFirst("p > img")?.attr("src")
            ?: document.select("meta[property='og:image']").attr("content")

        val imdbUrl = document.select("a[href*='imdb.com/title/']").attr("href")
        val imdbId = if (imdbUrl.isNotEmpty()) {
            imdbUrl.substringAfter("title/").substringBefore("/").substringBefore("?")
        } else {
            Regex("tt\\d{7,8}").find(document.html())?.value ?: ""
        }

        val pageHtml = document.html().lowercase()
        val tvtype = when {
            pageHtml.contains(Regex("series[- ]?(synopsis|info|plot|description)")) ||
            title.contains(Regex("(?i)(season\\s*\\d|complete|s\\d{1,2}e\\d|episode\\s*\\d)")) -> "series"
            else -> "movie"
        }

        var description = document.selectFirst(
            "h2:has(span:matches((?i)(SYNOPSIS|PLOT|Storyline|Description)))," +
            "h3:has(span:matches((?i)(SYNOPSIS|PLOT|Storyline|Description)))," +
            "h4:has(span:matches((?i)(SYNOPSIS|PLOT|Storyline|Description)))," +
            "strong:has(span:matches((?i)(SYNOPSIS|PLOT|Storyline|Description)))"
        )?.nextElementSibling()?.text()?.trim()

        var cast: List<String> = emptyList()
        var genre: List<String> = emptyList()
        var imdbRating = ""
        var year = ""
        var background = posterUrl
        var tmdbId: Int? = null

        if (imdbId.isNotEmpty()) {
            val tmdbData = fetchTmdbByImdb(imdbId, tvtype)
            if (tmdbData != null) {
                description = tmdbData.overview ?: description
                cast = tmdbData.cast
                genre = tmdbData.genres
                imdbRating = tmdbData.rating
                year = tmdbData.year
                posterUrl = tmdbData.poster ?: posterUrl
                background = tmdbData.backdrop ?: background
                tmdbId = tmdbData.id
            }
        }

        return if (tvtype == "series") {
            loadSeries(document, title, url, posterUrl, background, description, genre, imdbRating, year, cast, imdbUrl, imdbId, tmdbId)
        } else {
            loadMovie(document, title, url, posterUrl, background, description, genre, imdbRating, year, cast, imdbUrl, imdbId, tmdbId)
        }
    }

    private suspend fun loadMovie(
        document: org.jsoup.nodes.Document, title: String, url: String,
        posterUrl: String, background: String, description: String?,
        genre: List<String>, imdbRating: String, year: String,
        cast: List<String>, imdbUrl: String, imdbId: String, tmdbId: Int?
    ): LoadResponse {
        val buttons = document.select("a:has(button.dwd-button), a:has(.buttn), a:has(button)")
        val data = buttons.mapNotNull { button ->
            val link = fixUrl(button.attr("href"))
            try {
                val doc = app.get(link).document
                val source = doc.select(
                    "a:contains(V-Cloud), a:contains(Hub-Cloud), a:contains(G-Direct), a:contains(Filepress), a:contains(Download)"
                ).firstOrNull()?.attr("href")
                    ?: doc.select(
                        "a[href*='hubcloud'], a[href*='vcloud'], a[href*='gofile'], a[href*='gdflix'], a[href*='filepress'], a[href*='streamhg']"
                    ).firstOrNull()?.attr("href")
                    ?: ""
                Log.d("HdHub4u", "movie source: $source")
                if (source.isNotEmpty()) EpisodeLink(source) else null
            } catch (e: Exception) {
                Log.e("HdHub4u", "movie link resolve failed: ${e.message}")
                null
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genre
            this.score = Score.from10(imdbRating)
            this.year = year.toIntOrNull()
            this.backgroundPosterUrl = background
            addActors(cast)
            addImdbUrl(imdbUrl)
        }
    }

    private suspend fun loadSeries(
        document: org.jsoup.nodes.Document, title: String, url: String,
        posterUrl: String, background: String, description: String?,
        genre: List<String>, imdbRating: String, year: String,
        cast: List<String>, imdbUrl: String, imdbId: String, tmdbId: Int?
    ): LoadResponse {
        val hTags = document.select(
            "main h2:matches((?i)(4K|[0-9]*0p)), main h3:matches((?i)(4K|[0-9]*0p)), " +
            "main h4:matches((?i)(4K|[0-9]*0p)), main h5:matches((?i)(4K|[0-9]*0p))"
        ).filter { !it.text().contains("Zip", ignoreCase = true) }

        val episodesMap: MutableMap<Pair<Int, Int>, MutableList<String>> = mutableMapOf()

        for (tag in hTags) {
            val seasonMatch = Regex("""(?:Season |S)(\d+)""", RegexOption.IGNORE_CASE).find(tag.text())
            val realSeason = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val pTag = tag.nextElementSibling()
            val aTags: List<Element>? = if (pTag != null && pTag.tagName() == "p") {
                pTag.select("a").toList()
            } else { tag.select("a").toList() }

            var unilink = aTags?.find {
                it.text().contains(Regex("(?i)(V-Cloud|Hub-Cloud|G-Direct|Filepress|Episode|Download)"))
            }
            if (unilink == null && aTags != null) {
                unilink = aTags.firstOrNull { it.attr("href").contains("http") }
            }
            val Eurl = unilink?.attr("href") ?: continue

            try {
                val document2 = app.get(Eurl).document
                val vcloudLinks = document2.select("p > a").mapNotNull { el ->
                    val href = el.attr("href")
                    if (href.contains(Regex("(?i)(vcloud|hubcloud|gofile|gdflix|filepress|streamhg)"))) href
                    else null
                }
                if (vcloudLinks.isNotEmpty()) {
                    for ((idx, link) in vcloudLinks.withIndex()) {
                        val key = Pair(realSeason, idx + 1)
                        episodesMap.getOrPut(key) { mutableListOf() }.add(link)
                    }
                    Log.d("HdHub4u", "season $realSeason: ${vcloudLinks.size} episodes collected")
                }
            } catch (e: Exception) {
                Log.e("HdHub4u", "episode link resolve failed: ${e.message}")
            }
        }

        val tmdbEpisodes = if (tmdbId != null) fetchTmdbEpisodes(tmdbId) else emptyMap()
        val tvSeriesEpisodes = mutableListOf<Episode>()
        for ((key, value) in episodesMap) {
            val season = key.first
            val episode = key.second
            val episodeInfo = tmdbEpisodes["${season}_${episode}"]
            val data = value.map { source -> EpisodeLink(source) }
            tvSeriesEpisodes.add(
                newEpisode(data) {
                    this.name = episodeInfo?.name ?: "Episode $episode"
                    this.season = season
                    this.episode = episode
                    this.posterUrl = episodeInfo?.thumbnail
                    this.description = episodeInfo?.overview
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, tvSeriesEpisodes) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = genre
            this.score = Score.from10(imdbRating)
            this.year = year.toIntOrNull() ?: year.substringBefore("–").toIntOrNull()
            this.backgroundPosterUrl = background
            addActors(cast)
            addImdbUrl(imdbUrl)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = com.lagradost.cloudstream3.utils.AppUtils.parseJson<ArrayList<EpisodeLink>>(data)
        sources.amap {
            val source = it.source
            when {
                source.contains("vcloud", true) -> VCloud().getUrl(source, "", subtitleCallback, callback)
                source.contains("hubcloud", true) -> HubCloud().getUrl(source, "", subtitleCallback, callback)
                source.contains("gofile", true) -> Gofile().getUrl(source, "", subtitleCallback, callback)
                source.contains("gdflix", true) -> Gdflix().getUrl(source, "", subtitleCallback, callback)
                source.contains("filepress", true) -> Filepress().getUrl(source, "", subtitleCallback, callback)
                source.contains("streamhg", true) -> Streamhg().getUrl(source, "", subtitleCallback, callback)
                else -> loadExtractor(source, "", subtitleCallback, callback)
            }
        }
        return true
    }

    private data class TmdbData(
        val id: Int, val overview: String?, val cast: List<String>,
        val genres: List<String>, val rating: String, val year: String,
        val poster: String?, val backdrop: String?
    )

    private data class TmdbEpisodeInfo(val name: String, val overview: String?, val thumbnail: String?)

    private suspend fun fetchTmdbByImdb(imdbId: String, type: String): TmdbData? {
        if (imdbId.isEmpty()) return null
        val findUrl = "https://api.themoviedb.org/3/find/$imdbId?api_key=${tmdbKeys.first()}&external_source=imdb_id"
        return try {
            val findResp = app.get(findUrl, timeout = 15).text
            val findJson = JSONObject(findResp)
            val results = if (type == "series") findJson.optJSONArray("tv_results") else findJson.optJSONArray("movie_results")
            val first = results?.optJSONObject(0) ?: return null
            val tmdbId = first.optInt("id")
            val mediaType = if (type == "series") "tv" else "movie"
            val detailUrl = "https://api.themoviedb.org/3/$mediaType/$tmdbId?api_key=${tmdbKeys.first()}&append_to_response=credits&language=en-US"
            val detailResp = app.get(detailUrl, timeout = 15).text
            val detail = JSONObject(detailResp)

            val genres = mutableListOf<String>()
            detail.optJSONArray("genres")?.let { arr ->
                for (i in 0 until arr.length()) genres.add(arr.getJSONObject(i).optString("name"))
            }
            val castList = mutableListOf<String>()
            detail.optJSONObject("credits")?.optJSONArray("cast")?.let { arr ->
                for (i in 0 until minOf(arr.length(), 10)) {
                    castList.add(arr.getJSONObject(i).optString("name"))
                }
            }
            val dateStr = if (mediaType == "tv") detail.optString("first_air_date") else detail.optString("release_date")
            val yearStr = if (dateStr.length >= 4) dateStr.substring(0, 4) else ""
            val rating = String.format("%.1f", detail.optDouble("vote_average"))
            val posterUrl = detail.optString("poster_path").ifEmpty { null }?.let { "$tmdbImageBase$it" }
            val backdropUrl = detail.optString("backdrop_path").ifEmpty { null }?.let { "$tmdbImageBase$it" }

            TmdbData(tmdbId, detail.optString("overview").ifEmpty { null }, castList, genres, rating, yearStr, posterUrl, backdropUrl)
        } catch (e: Exception) {
            Log.e("HdHub4u", "TMDB fetch failed for $imdbId: ${e.message}")
            null
        }
    }

    private suspend fun fetchTmdbEpisodes(tmdbId: Int): Map<String, TmdbEpisodeInfo> {
        return try {
            val seasonUrl = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=${tmdbKeys.first()}&language=en-US"
            val seriesResp = app.get(seasonUrl, timeout = 15).text
            val seriesJson = JSONObject(seriesResp)
            val seasons = seriesJson.optJSONArray("seasons") ?: return emptyMap()
            val map = mutableMapOf<String, TmdbEpisodeInfo>()
            for (i in 0 until seasons.length()) {
                val seasonObj = seasons.getJSONObject(i)
                val seasonNum = seasonObj.optInt("season_number")
                if (seasonNum == 0) continue
                val seasonDetailUrl = "https://api.themoviedb.org/3/tv/$tmdbId/season/$seasonNum?api_key=${tmdbKeys.first()}&language=en-US"
                val seasonResp = app.get(seasonDetailUrl, timeout = 15).text
                val seasonJson = JSONObject(seasonResp)
                val episodes = seasonJson.optJSONArray("episodes") ?: continue
                for (j in 0 until episodes.length()) {
                    val ep = episodes.getJSONObject(j)
                    val epNum = ep.optInt("episode_number")
                    map["${seasonNum}_${epNum}"] = TmdbEpisodeInfo(
                        ep.optString("name").ifEmpty { "Episode $epNum" },
                        ep.optString("overview").ifEmpty { null },
                        ep.optString("still_path").ifEmpty { null }?.let { "$tmdbImageBase$it" }
                    )
                }
            }
            map
        } catch (e: Exception) {
            Log.e("HdHub4u", "TMDB episodes fetch failed: ${e.message}")
            emptyMap()
        }
    }

    data class EpisodeLink(val source: String)
}
