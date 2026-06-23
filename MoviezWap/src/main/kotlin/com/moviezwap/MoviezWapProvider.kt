package com.moviezwap

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.amap
import org.jsoup.Jsoup
import java.net.URLEncoder

class MoviezWapProvider : MainAPI() {
    override var mainUrl = "https://www.moviezwap.onl"
    override var name = "MoviezWap"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "te"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        Pair("category/Telugu-(2026)-Movies.html", "Telugu (2026) Movies"),
        Pair("category/Telugu-(2025)-Movies.html", "Telugu (2025) Movies"),
        Pair("category/Telugu-(2024)-Movies.html", "Telugu (2024) Movies"),
        Pair("category/Tamil-(2026)-Movies.html", "Tamil (2026) Movies"),
        Pair("category/Tamil-(2025)-Movies.html", "Tamil (2025) Movies"),
        Pair("category/Tamil-(2024)-Movies.html", "Tamil (2024) Movies"),
        Pair("category/Telugu-Web-Series.html", "Telugu Web Series"),
        Pair("category/Telugu-Dubbed-Movies-[Hollywood].html", "Telugu Dubbed Hollywood"),
        Pair("category/Tamil-Dubbed-Movies-[Hollywood].html", "Tamil Dubbed Hollywood"),
        Pair("category/Hindi-New-Movies.html", "Hindi New Movies"),
        Pair("category/Malayalam-New-Movies.html", "Malayalam New Movies"),
        Pair("category/Kannada-Mobile-Movies.html", "Kannada Movies"),
        Pair("category/Hollywood-New-Movies.html", "Hollywood English Movies"),
        Pair("category/HOT-Web-Series.html", "HOT Web Series")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            val cleanData = request.data.substringBefore(".html")
            "$mainUrl/$cleanData/$page.html"
        }

        val html = app.get(url).text
        val doc = Jsoup.parse(html)

        val elements = doc.select("div.mylist:has(img)")
        val searchList = elements.amap { element ->
            val a = element.selectFirst("a") ?: return@amap null
            val title = a.text().trim()
            val href = fixUrl(a.attr("href"))
            val isSeries = title.contains("Season", ignoreCase = true) || 
                           title.contains("Eps", ignoreCase = true) || 
                           title.contains("Series", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie
            val posterUrl = getCinemetaPoster(title, isSeries)

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }.filterNotNull()

        return newHomePageResponse(request.name, searchList, hasNext = searchList.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search.php?q=$encodedQuery"
        val html = app.get(url).text
        val doc = Jsoup.parse(html)

        val elements = doc.select("div.mylist:has(img)")
        return elements.amap { element ->
            val a = element.selectFirst("a") ?: return@amap null
            val title = a.text().trim()
            val href = fixUrl(a.attr("href"))
            val isSeries = title.contains("Season", ignoreCase = true) || 
                           title.contains("Eps", ignoreCase = true) || 
                           title.contains("Series", ignoreCase = true)
            val type = if (isSeries) TvType.TvSeries else TvType.Movie
            val posterUrl = getCinemetaPoster(title, isSeries)

            newMovieSearchResponse(title, href, type) {
                this.posterUrl = posterUrl
            }
        }.filterNotNull()
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("div.cat h2")?.text()?.trim() 
            ?: doc.selectFirst("title")?.text()?.substringBefore("Full Movie")?.trim() 
            ?: "Movie"

        val scrapedPoster = doc.selectFirst("div.mylist img[src*=\"/poster/\"]")?.attr("src")?.let { fixUrl(it) }

        val movies = doc.select("div.movie")
        val genre = movies.firstOrNull { it.text().contains("Genre", ignoreCase = true) }
            ?.text()?.substringAfter(":")?.trim()

        val plot = movies.firstOrNull { it.text().contains("Desc/Plot", ignoreCase = true) }
            ?.text()?.substringAfter(":")?.trim()

        val catListLinks = doc.select("div.catList a")
        val childEpisodes = catListLinks.filter { it.attr("href").contains("/movie/") }
        val isSeries = childEpisodes.isNotEmpty()


        var imdbId: String? = null
        var tmdbId: String? = null
        var officialPlot: String? = plot
        var officialPoster: String? = scrapedPoster
        var backdropUrl: String? = null
        var officialRating: Double? = null
        var officialCast: List<String>? = null

        try {
            val cleanName = cleanTitle(title)
            val searchType = if (isSeries) "series" else "movie"
            val searchUrl = "https://v3-cinemeta.strem.io/catalog/$searchType/top/search=${URLEncoder.encode(cleanName, "UTF-8")}.json"
            val searchResponseText = app.get(searchUrl).text
            val searchData = mapper.readValue<CinemetaSearchResponse>(searchResponseText)
            val firstMatch = searchData.metas?.firstOrNull()
            val resolvedImdbId = firstMatch?.imdbId ?: firstMatch?.id

            if (resolvedImdbId != null) {
                imdbId = resolvedImdbId
                val detailsUrl = "https://v3-cinemeta.strem.io/meta/$searchType/$imdbId.json"
                val detailsResponseText = app.get(detailsUrl).text
                val detailsData = mapper.readValue<CinemetaResponse>(detailsResponseText)
                val meta = detailsData.meta
                if (meta != null) {
                    tmdbId = meta.moviedbId?.toString()
                    officialPlot = meta.description ?: plot
                    officialPoster = meta.poster ?: scrapedPoster
                    backdropUrl = meta.background
                    officialRating = meta.imdbRating?.toString()?.toDoubleOrNull()
                    officialCast = meta.cast
                }
            }
        } catch (e: Exception) {

        }

        if (isSeries) {

            val episodes = childEpisodes.mapIndexed { index, el ->
                newEpisode(fixUrl(el.attr("href"))) {
                    this.name = el.text().trim()
                    this.episode = index + 1
                    this.season = 1
                }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = officialPoster
                this.plot = officialPlot
                this.backgroundPosterUrl = backdropUrl
                this.tags = genre?.split(",")?.map { it.trim() }
                if (officialRating != null) {
                    this.score = Score.from10(officialRating)
                }
                if (officialCast != null) {
                    addActors(officialCast)
                }
                if (imdbId != null) {
                    addImdbId(imdbId)
                }
                if (tmdbId != null) {
                    addTMDbId(tmdbId)
                }
            }
        } else {

            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = officialPoster
                this.plot = officialPlot
                this.backgroundPosterUrl = backdropUrl
                this.tags = genre?.split(",")?.map { it.trim() }
                if (officialRating != null) {
                    this.score = Score.from10(officialRating)
                }
                if (officialCast != null) {
                    addActors(officialCast)
                }
                if (imdbId != null) {
                    addImdbId(imdbId)
                }
                if (tmdbId != null) {
                    addTMDbId(tmdbId)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = app.get(data).text
        val doc = Jsoup.parse(html)

        // Find download links
        val dwloadLinks = doc.select("div.catList a[href*=\"dwload.php?file=\"]")

        for (link in dwloadLinks) {
            val label = link.text().trim()
            val dwloadUrl = fixUrl(link.attr("href"))

            try {

                val dwloadHtml = app.get(dwloadUrl).text
                val dwloadDoc = Jsoup.parse(dwloadHtml)
                val downloadHref = dwloadDoc.selectFirst("a[href*=\"download.php?file=\"]")?.attr("href") ?: continue
                val downloadUrl = fixUrl(downloadHref)


                val downloadHtml = app.get(downloadUrl).text
                val downloadDoc = Jsoup.parse(downloadHtml)


                val directVideoUrl = downloadDoc.select("a").firstOrNull { 
                    it.attr("href").contains(".mp4", ignoreCase = true) 
                }?.attr("href")

                if (directVideoUrl != null) {
                    val quality = when {
                        label.contains("720p", ignoreCase = true) -> 720
                        label.contains("480p", ignoreCase = true) -> 480
                        label.contains("360p", ignoreCase = true) || label.contains("320p", ignoreCase = true) -> 360
                        label.contains("240p", ignoreCase = true) -> 240
                        else -> 360
                    }

                    callback(
                        newExtractorLink(
                            source = name,
                            name = label,
                            url = fixUrl(directVideoUrl)
                        ) {
                            this.quality = quality
                            this.referer = "$mainUrl/"
                        }
                    )
                }
            } catch (e: Exception) {

            }
        }

        return true
    }

    private fun cleanTitle(title: String): String {
        var clean = title

        clean = clean.replace(Regex("""\([\d]{4}\)"""), "")
        clean = clean.replace(Regex("""\[[\d]{4}\]"""), "")
        

        clean = clean.replace(Regex("""(?i)\bSeason\s*\d+\b"""), "")
        clean = clean.replace(Regex("""(?i)\bPart\s*\d+\b"""), "")
        clean = clean.replace(Regex("""(?i)\bs\d+\b"""), "")
        clean = clean.replace(Regex("""(?i)\bEps?\s*\d+(-\d+)?\b"""), "")
        clean = clean.replace(Regex("""(?i)\bEpisodes?\s*\d+(-\d+)?\b"""), "")


        val tags = listOf(
            "Telugu Dubbed", "Tamil Dubbed", "Hindi Dubbed", "Malayalam Dubbed", "English Dubbed",
            "Telugu", "Tamil", "Hindi", "Malayalam", "English", "Kannada",
            "Dubbed", "ORG", "Original", "HDRip", "WebRip", "BRRip", "Blu-Ray", "Bluray", "HQ", "LQ",
            "Single Video", "Full Movie", "Dual Audio", "Multi Audio", "Rip"
        )
        for (tag in tags) {
            clean = clean.replace(Regex("(?i)\\b$tag\\b"), "")
        }


        clean = clean.replace(Regex("""[-–_().\[\]:]"""), " ")

        clean = clean.replace(Regex("""\s+"""), " ").trim()
        return clean
    }

    private suspend fun getCinemetaPoster(title: String, isSeries: Boolean): String? {
        try {
            val cleanName = cleanTitle(title)
            val searchType = if (isSeries) "series" else "movie"
            val searchUrl = "https://v3-cinemeta.strem.io/catalog/$searchType/top/search=${URLEncoder.encode(cleanName, "UTF-8")}.json"
            val searchResponseText = app.get(searchUrl).text
            val searchData = mapper.readValue<CinemetaSearchResponse>(searchResponseText)
            return searchData.metas?.firstOrNull()?.poster
        } catch (e: Exception) {
            return null
        }
    }
}

data class CinemetaSearchItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("imdb_id") val imdbId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("poster") val poster: String? = null
)

data class CinemetaSearchResponse(
    @JsonProperty("metas") val metas: List<CinemetaSearchItem>? = null
)

data class CinemetaVideo(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("episode") val episode: Int? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("released") val released: String? = null
)

data class CinemetaMeta(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("imdbRating") val imdbRating: Any? = null,
    @JsonProperty("poster") val poster: String? = null,
    @JsonProperty("background") val background: String? = null,
    @JsonProperty("logo") val logo: String? = null,
    @JsonProperty("moviedb_id") val moviedbId: Any? = null,
    @JsonProperty("cast") val cast: List<String>? = null,
    @JsonProperty("videos") val videos: List<CinemetaVideo>? = null
)

data class CinemetaResponse(
    @JsonProperty("meta") val meta: CinemetaMeta? = null
)
