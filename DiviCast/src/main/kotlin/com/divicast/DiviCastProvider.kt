package com.divicast

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import com.fasterxml.jackson.annotation.JsonProperty
import org.jsoup.nodes.Element

/**
 * Provider for DiviCast using its unprotected WordPress backend directly.
 */
class DiviCastProvider : MainAPI() {
    override var mainUrl = "https://film2.pushkro.in/plagiarism"
    override var name = "DiviCast"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false

    private val frontUrl = "https://divicast.study"

    private val headersMap = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
    )



    override val mainPage = mainPageOf(
        Pair("$mainUrl/movies/", "Movies"),
        Pair("$mainUrl/series/", "TV Series"),
        Pair("$mainUrl/genre/action/", "Action"),
        Pair("$mainUrl/genre/comedy/", "Comedy"),
        Pair("$mainUrl/genre/drama/", "Drama"),
        Pair("$mainUrl/genre/thriller/", "Thriller"),
        Pair("$mainUrl/genre/horror/", "Horror"),
        Pair("$mainUrl/genre/crime/", "Crime"),
        Pair("$mainUrl/genre/science-fiction/", "Sci-Fi"),
        Pair("$mainUrl/genre/romance/", "Romance"),
        Pair("$mainUrl/most-popular/", "Most Popular"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            "${request.data.trimEnd('/')}/page/$page/"
        } else {
            request.data
        }

        val document = app.get(url, headers = headersMap).document
        val items = document.select("article.box").mapNotNull { article ->
            article.toSearchResponse()
        }

        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }



    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url, headers = headersMap).document
        return document.select("article.box").mapNotNull { article ->
            article.toSearchResponse()
        }
    }



    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headersMap).document


        val title = document.selectFirst("h1.entry-title")?.text()?.trim().orEmpty()
        val plotLocal = document.selectFirst(".entry-content > p")?.text()?.trim().orEmpty()
        val posterLocal = cleanPosterUrl(
            document.selectFirst(".limage img")?.attr("src")
                ?: document.selectFirst("img")?.attr("src")
        )
        val ratingStr = document.selectFirst(".rating-prc [itemprop=ratingValue]")?.text()
        val ratingLocal = ratingStr?.toFloatOrNull()
        val yearStr = document.selectFirst(".entry-content ul.data li:contains(Release) time")
            ?.attr("datetime")?.substring(0, 4)?.toIntOrNull()
        val genres = document.select(".entry-content ul.data li:contains(Genre) a")
            .map { it.text().trim() }
        val actorsLocal = document.select(".entry-content ul.data li:contains(Stars) a[itemprop=name]")
            .map { ActorData(Actor(it.text().trim())) }
        val statusText = document.selectFirst(".entry-content ul.data li:contains(Status) span")
            ?.text()?.trim()


        val isSeries = url.contains("/series/") || document.select(".ts-ep-list").isNotEmpty()


        var imdbId = extractImdbId(document)
        var tmdbId: String? = null

        // Fetch first episode page for series ID
        if (imdbId == null && isSeries) {
            val firstEpLink = document.selectFirst(".ts-ep-list .epsdlist li a[href]")?.attr("href")
            if (firstEpLink != null) {
                try {
                    val epDoc = app.get(fixUrl(firstEpLink), headers = headersMap).document
                    imdbId = extractImdbId(epDoc)
                    if (imdbId == null) {
                        val parsedTmdbId = extractTmdbId(epDoc)
                        if (parsedTmdbId != null) {
                            val tmdbApiKey = "1865f43a0549ca50d341dd9ab8b29f49"
                            val extUrl = "https://api.themoviedb.org/3/tv/$parsedTmdbId/external_ids?api_key=$tmdbApiKey"
                            val extResponse = app.get(extUrl, headers = headersMap)
                            if (extResponse.code == 200) {
                                val extIds = extResponse.parsedSafe<TmdbExternalIds>()
                                imdbId = extIds?.imdbId
                            }
                        }
                    }
                } catch (_: Exception) {

                }
            }
        }


        var enrichedPlot = plotLocal
        var enrichedPoster = posterLocal
        var enrichedBg: String? = null
        var enrichedRating = ratingLocal
        var enrichedYear = yearStr
        var enrichedActors = actorsLocal
        var cinemetaVideos: List<CinemetaVideo>? = null
        var resolvedTmdbId: String? = null

        if (imdbId != null) {
            try {
                val metaType = if (isSeries) "series" else "movie"
                val metaUrl = "https://v3-cinemeta.strem.io/meta/$metaType/$imdbId.json"
                val metaResponse = app.get(metaUrl, headers = headersMap)
                if (metaResponse.code == 200) {
                    val metaJson = metaResponse.parsedSafe<CinemetaResponse>()
                    val meta = metaJson?.meta
                    if (meta != null) {
                        meta.description?.takeIf { it.isNotBlank() }?.let { enrichedPlot = it }
                        meta.poster?.takeIf { it.isNotBlank() }?.let { enrichedPoster = cleanPosterUrl(it) }
                        meta.background?.takeIf { it.isNotBlank() }?.let { enrichedBg = cleanPosterUrl(it) }
                        meta.imdbRating?.toFloatOrNull()?.let { enrichedRating = it }
                        meta.year?.toIntOrNull()?.let { enrichedYear = it }
                        meta.moviedbId?.let { resolvedTmdbId = it.toString() }
                        cinemetaVideos = meta.videos
                        val castList = meta.cast ?: emptyList()
                        if (castList.isNotEmpty()) {
                            enrichedActors = castList.map { ActorData(Actor(it)) }
                        }
                    }
                }
            } catch (_: Exception) {
        
            }
        }

        if (isSeries) {
            val episodes = parseEpisodes(document, url, cinemetaVideos)
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = enrichedPoster
                this.backgroundPosterUrl = enrichedBg
                this.plot = enrichedPlot
                this.year = enrichedYear
                this.score = enrichedRating?.let { Score.from10(it) }
                this.tags = genres
                this.actors = enrichedActors
                if (imdbId != null) addImdbId(imdbId)
                val finalTmdbId = resolvedTmdbId ?: tmdbId
                if (finalTmdbId != null) addTMDbId(finalTmdbId)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = enrichedPoster
                this.backgroundPosterUrl = enrichedBg
                this.plot = enrichedPlot
                this.year = enrichedYear
                this.score = enrichedRating?.let { Score.from10(it) }
                this.tags = genres
                this.actors = enrichedActors
                if (imdbId != null) addImdbId(imdbId)
                if (resolvedTmdbId != null) addTMDbId(resolvedTmdbId)
            }
        }
    }



    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headersMap).document

        // Extract server links from data-em
        val serverTabs = document.select("ul.mirror a[data-em]")
        
        var imdbId: String? = null
        var tmdbId: String? = null
        var season: String? = null
        var episode: String? = null

        val movieRegex = Regex("""/movie/(tt\d+)""")
        val tvRegex1 = Regex("""/tv/(\d+)/(\d+)/(\d+)""")
        val tvRegex2 = Regex("""/tv/(\d+)-(\d+)-(\d+)""")
        val tvRegex3 = Regex("""/tv/tmdb/(\d+)-(\d+)-(\d+)""")

        for (tab in serverTabs) {
            val dataEm = tab.attr("data-em")
            if (dataEm.isBlank()) continue

            try {
                val decoded = String(base64Decode(dataEm), Charsets.UTF_8).trim()
                val srcRegex = Regex("""src="([^"]+)"""")
                val srcMatch = srcRegex.find(decoded)
                val embedUrl = srcMatch?.groupValues?.get(1) ?: continue

                // Skip trailers
                if (embedUrl.contains("youtube.com") || embedUrl.contains("youtu.be")) continue

                val embedUrlClean = embedUrl.replace("https://", "").replace("http://", "")
                val movieMatch = movieRegex.find(embedUrlClean)
                if (movieMatch != null) {
                    imdbId = movieMatch.groupValues[1]
                    break
                }

                val tvMatch1 = tvRegex1.find(embedUrlClean)
                val tvMatch2 = tvRegex2.find(embedUrlClean)
                val tvMatch3 = tvRegex3.find(embedUrlClean)
                val tvMatch = tvMatch1 ?: tvMatch2 ?: tvMatch3
                if (tvMatch != null) {
                    tmdbId = tvMatch.groupValues[1]
                    season = tvMatch.groupValues[2]
                    episode = tvMatch.groupValues[3]
                    break
                }
            } catch (_: Exception) {
                continue
            }
        }

        // Fallback: Parse season/episode from URL
        if (season == null || episode == null) {
            val watchRegex = Regex("""/watch/.*?-(\d+)x(\d+)/""", RegexOption.IGNORE_CASE)
            val watchMatch = watchRegex.find(data)
            if (watchMatch != null) {
                season = watchMatch.groupValues[1]
                episode = watchMatch.groupValues[2]
            }
        }


        var apiUrl: String? = null
        if (imdbId != null) {
            if (season != null && episode != null) {
                apiUrl = "https://streamdata.vaplayer.ru/api.php?imdb=$imdbId&type=tv&season=$season&episode=$episode"
            } else {
                apiUrl = "https://streamdata.vaplayer.ru/api.php?imdb=$imdbId&type=movie"
            }
        } else if (tmdbId != null && season != null && episode != null) {
            apiUrl = "https://streamdata.vaplayer.ru/api.php?tmdb=$tmdbId&type=tv&season=$season&episode=$episode"
        }

        if (apiUrl != null) {
            try {
                val response = app.get(
                    apiUrl,
                    headers = mapOf(
                        "Referer" to "https://nextgencloudfabric.com/",
                        "Origin" to "https://nextgencloudfabric.com"
                    )
                )
                if (response.code == 200) {
                    val json = response.parsedSafe<VaplayerResponse>()
                    val streamUrls = json?.data?.streamUrls
                    if (streamUrls != null && streamUrls.isNotEmpty()) {
                        streamUrls.forEachIndexed { index, streamUrl ->
                            try {
                                val parsedLinks = generateM3u8(
                                    source = name,
                                    streamUrl = streamUrl,
                                    referer = "https://nextgencloudfabric.com/",
                                    headers = mapOf(
                                        "Origin" to "https://nextgencloudfabric.com",
                                        "Referer" to "https://nextgencloudfabric.com/"
                                    ),
                                    name = "DiviCast [HLS Stream ${index + 1}]"
                                )
                                parsedLinks.forEach { callback(it) }
                            } catch (e: Exception) {

                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "DiviCast [HLS Stream ${index + 1}]",
                                        url = streamUrl
                                    ) {
                                        this.quality = Qualities.P720.value
                                        this.headers = mapOf(
                                            "Origin" to "https://nextgencloudfabric.com",
                                            "Referer" to "https://nextgencloudfabric.com/"
                                        )
                                    }
                                )
                            }
                        }


                        json.defaultSubs?.forEach { sub ->
                            val subUrl = sub.url ?: sub.file
                            if (!subUrl.isNullOrBlank()) {
                                // Force HTTPS to avoid Cleartext HTTP traffic exception
                                val secureSubUrl = subUrl.replace("http://", "https://")
                                subtitleCallback(
                                    SubtitleFile(
                                        sub.label ?: sub.lang ?: sub.code ?: "Subtitle",
                                        secureSubUrl
                                    )
                                )
                            }
                        }


                        return true
                    }
                }
            } catch (e: Exception) {

            }
        }

        return false
    }



    /** Convert article.box to SearchResponse. */
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkEl = this.selectFirst("a.tip[href]") ?: this.selectFirst("a[href]")
        val href = linkEl?.attr("href") ?: return null
        val title = this.selectFirst(".entry-title")?.text()?.trim() ?: return null
        val poster = cleanPosterUrl(this.selectFirst("img")?.attr("src"))
        val yearStr = this.selectFirst(".addyear")?.text()?.trim()
        val year = yearStr?.toIntOrNull()
        val typeText = this.selectFirst(".type")?.text()?.trim() ?: "Movie"

        val fixedHref = fixUrl(href)

        val tvType = when {
            typeText.equals("TV", ignoreCase = true) ||
            typeText.equals("TV Series", ignoreCase = true) -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (tvType == TvType.TvSeries) {
            newTvSeriesSearchResponse(title, fixedHref) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, fixedHref) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    /** Parse episodes from series page. */
    private fun parseEpisodes(
        document: org.jsoup.nodes.Document,
        seriesUrl: String,
        cinemetaVideos: List<CinemetaVideo>?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val epItems = document.select(".ts-ep-list .epsdlist li")

        for (item in epItems) {
            val link = item.selectFirst("a[href]") ?: continue
            val href = fixUrl(link.attr("href"))
            val epNumText = item.selectFirst(".epl-num")?.text()?.trim() ?: continue
            val epTitle = item.selectFirst(".epl-title")?.text()?.trim() ?: ""

    
            val epRegex = Regex("""S(\d+)\s*EP\s*(\d+)""", RegexOption.IGNORE_CASE)
            val match = epRegex.find(epNumText)

            val season = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = match?.groupValues?.get(2)?.toIntOrNull() ?: continue

            val matchedVideo = cinemetaVideos?.find { it.season == season && it.episode == episode }

            episodes.add(
                newEpisode(href) {
                    this.name = matchedVideo?.name?.takeIf { it.isNotBlank() } ?: epTitle.ifBlank { "Episode $episode" }
                    this.season = season
                    this.episode = episode
                    this.posterUrl = matchedVideo?.thumbnail
                    this.description = matchedVideo?.description ?: matchedVideo?.overview
                }
            )
        }


        return episodes.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /** Extract IMDb ID from detail page. */
    private fun extractImdbId(document: org.jsoup.nodes.Document): String? {

        val iframeSrc = document.selectFirst("iframe.video")?.attr("src")
        if (iframeSrc != null) {
            val imdbMatch = Regex("""tt\d+""").find(iframeSrc)
            if (imdbMatch != null) return imdbMatch.value
        }


        val serverTabs = document.select("ul.mirror a[data-em]")
        for (tab in serverTabs) {
            val dataEm = tab.attr("data-em")
            if (dataEm.isBlank()) continue
            try {
                val decoded = String(base64Decode(dataEm), Charsets.UTF_8)
                val imdbMatch = Regex("""tt\d+""").find(decoded)
                if (imdbMatch != null) return imdbMatch.value
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    private fun extractTmdbId(document: org.jsoup.nodes.Document): String? {
        val iframeSrc = document.selectFirst("iframe.video")?.attr("src")
        val tvRegex = Regex("""/tv/(?:tmdb/)?(\d+)""")

        if (iframeSrc != null) {
            val match = tvRegex.find(iframeSrc)
            if (match != null) return match.groupValues[1]
        }

        val serverTabs = document.select("ul.mirror a[data-em]")
        for (tab in serverTabs) {
            val dataEm = tab.attr("data-em")
            if (dataEm.isBlank()) continue
            try {
                val decoded = String(base64Decode(dataEm), Charsets.UTF_8).trim()
                val match = tvRegex.find(decoded)
                if (match != null) return match.groupValues[1]
            } catch (_: Exception) {
                continue
            }
        }
        return null
    }

    /** Get quality from server name. */
    private fun getQualityFromServerName(serverName: String): Int {
        return when {
            serverName.contains("4K", ignoreCase = true) -> Qualities.P2160.value
            serverName.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            serverName.contains("720", ignoreCase = true) -> Qualities.P720.value
            serverName.contains("480", ignoreCase = true) -> Qualities.P480.value
            serverName.contains("CAM", ignoreCase = true) ||
            serverName.contains("TS", ignoreCase = true) -> Qualities.Unknown.value
            else -> Qualities.P720.value
        }
    }

    /** Get server label. */
    private fun getServerLabel(embedUrl: String): String {
        val hostLabel = when {
            embedUrl.contains("vidfast") -> "VidFast"
            embedUrl.contains("vidlink") -> "VidLink"
            embedUrl.contains("vidnest") -> "VidNest"
            embedUrl.contains("moviesapi") -> "MoviesApi"
            embedUrl.contains("autoembed") -> "AutoEmbed"
            embedUrl.contains("vidrock") -> "VidRock"
            embedUrl.contains("vidzee") -> "VidZee"
            embedUrl.contains("nontongo") -> "NontonGo"
            else -> "Server"
        }
        return "DiviCast [$hostLabel]"
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            Base64.decode(str, Base64.DEFAULT)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    /** Fix relative URL. */
    private fun fixUrl(href: String): String {
        return when {
            href.startsWith("/") -> "$mainUrl$href"
            href.startsWith("http") -> href
            else -> "$mainUrl/$href"
        }
    }

    private fun cleanPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        var fixed = fixUrl(url)
        val wpRegex = Regex("""https?://i\d+\.wp\.com/""")
        fixed = fixed.replace(wpRegex, "https://")
        if (fixed.startsWith("http://")) {
            fixed = fixed.replaceFirst("http://", "https://")
        }
        return fixed
    }



    data class CinemetaResponse(
        val meta: CinemetaMeta? = null
    )

    data class CinemetaMeta(
        val name: String? = null,
        val description: String? = null,
        val poster: String? = null,
        val background: String? = null,
        val imdbRating: String? = null,
        val year: String? = null,
        @JsonProperty("moviedb_id")
        val moviedbId: Int? = null,
        val cast: List<String>? = null,
        val genre: List<String>? = null,
        val videos: List<CinemetaVideo>? = null,
    )

    data class CinemetaVideo(
        val name: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val thumbnail: String? = null,
        val description: String? = null,
        val overview: String? = null,
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null
    )

    data class VaplayerResponse(
        @JsonProperty("status_code") val statusCode: String? = null,
        val data: VaplayerData? = null,
        @JsonProperty("default_subs") val defaultSubs: List<VaplayerSub>? = null,
        @JsonProperty("thumbnails_url") val thumbnailsUrl: String? = null
    )

    data class VaplayerData(
        val title: String? = null,
        @JsonProperty("imdb_id") val imdbId: String? = null,
        @JsonProperty("stream_urls") val streamUrls: List<String>? = null
    )

    data class VaplayerSub(
        val file: String? = null,
        val url: String? = null,
        val label: String? = null,
        val lang: String? = null,
        val code: String? = null
    )

}
