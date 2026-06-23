package com.mkvbase

import com.lagradost.api.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbUrl
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchQuality
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.toNewSearchResponseList
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.amap
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer

/**
 * Provider for MkVBase (https://mkvbase.site) leveraging custom GDFlix/HubCloud extractors and TMDB metadata.
 */
class MkVBaseProvider : MainAPI() {
    override var mainUrl = "https://mkvbase.site"
    override var name = "MkVBase"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.Others
    )
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true

    companion object {
        private const val TAG = "MkVBase"

        // TMDB API key
        private const val TMDB_API_KEY = "1865f43a0549ca50d341dd9ab8b29f49"
        private const val TMDB_API = "https://api.themoviedb.org/3"
        private const val TMDB_IMG = "https://image.tmdb.org/t/p/original"
    }


    private val httpHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "X-Requested-With" to "XMLHttpRequest",
        "Referer" to "$mainUrl/"
    )

    private suspend fun getJson(path: String): String {
        val url = if (path.startsWith("http")) path else "$mainUrl$path"
        return app.get(url, headers = httpHeaders).text
    }


    data class MkVLink(
        val id: Long,
        val title: String,
        val url: String,
        @JsonProperty("created_at") val createdAt: String
    )

    internal data class MkVSearchResponse(
        val results: List<MkVLink>?,
        val count: Int?
    )

    internal data class MkVTrendingResponse(
        val trending: List<String>?
    )

    /** Internal payload for loadLinks. */
    internal data class MkVEpisodePayload(
        val url: String,
        val host: String,
        val title: String
    )

    internal data class MkVGroupPayload(
        val title: String,
        val year: Int?,
        val links: List<MkVLink>
    )


    /** Strip leading source prefix. */
    private fun stripSourcePrefix(title: String): String {
        val idx = title.indexOf(" | ")
        return if (idx in 1..32) title.substring(idx + 3).trim() else title.trim()
    }

    /** Extract bare hostname. */
    private fun hostOf(url: String): String {
        return try {
            URI(url).host.replace("www.", "")
        } catch (e: Exception) {
            "unknown"
        }
    }

    /** Extract ISO date portion. */
    private fun dateOnly(iso: String): String {
        return try { iso.substringBefore('T') } catch (e: Exception) { "" }
    }

    /** Parse release filename. */
    internal data class ParsedTitle(
        val title: String,
        val year: Int?,
        val isSeries: Boolean,
        val season: Int?,
        val episode: Int?,
        val quality: String?
    )

    internal fun parseTitle(raw: String): ParsedTitle {
        val noExt = raw.replace(Regex("\\.(mkv|mp4|avi|mov|wmv|flv|m4v|ts|rar|zip)$", RegexOption.IGNORE_CASE), "")

        val stripped = stripSourcePrefix(noExt)

        val normalized = stripped
            .replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        val yearMatch = Regex("\\b(19[2-9]\\d|20\\d{2})\\b").find(normalized)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val titlePart = if (yearMatch != null) {
            normalized.substring(0, yearMatch.range.first).trim()
        } else {
            val cut = Regex(
                "\\b(480p|720p|1080p|2160p|4K|UHD|WEB[- ]?DL|WEBRIP|BLURAY|HDRIP|CAM|TS|AMZN|NF|HDTV|HEVC|x265|x264|H\\.?26[45]|DDP|AAC|AC3|DTS|MULTi|DUAL|ESub|ESubs|HIN|ENG|Tamil|Telugu|Hindi)\\b",
                RegexOption.IGNORE_CASE
            ).find(normalized)?.range?.first ?: normalized.length
            normalized.substring(0, cut).trim()
        }.ifBlank { normalized }

        val sxxexx = Regex("\\bS(\\d{1,2})E(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(normalized)
        val seasonOnly = Regex("\\bS(\\d{1,2})\\b(?!E)", RegexOption.IGNORE_CASE).find(normalized)
        val seasonWord = Regex("\\bSeason\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE).find(normalized)
        val epWord = Regex("\\bEpisode\\s*(\\d{1,3})\\b", RegexOption.IGNORE_CASE).find(normalized)

        val isSeries = sxxexx != null || seasonOnly != null || seasonWord != null
        val season = sxxexx?.groupValues?.get(1)?.toIntOrNull()
            ?: seasonOnly?.groupValues?.get(1)?.toIntOrNull()
            ?: seasonWord?.groupValues?.get(1)?.toIntOrNull()
        val episode = sxxexx?.groupValues?.get(2)?.toIntOrNull()
            ?: epWord?.groupValues?.get(1)?.toIntOrNull()

        val quality = Regex("\\b(2160p|1080p|720p|480p|4K|UHD)\\b", RegexOption.IGNORE_CASE)
            .find(normalized)?.value

        val cleanTitle = titlePart
            .replace(Regex("^(?:\\s*(?:\\[.*?\\]|\\(.*?\\)))+"), "")
            .replace(Regex("\\s*\\[.*?$"), "")
            .replace(Regex("\\s*\\(.*?$"), "")
            .trim()

        return ParsedTitle(
            title = cleanTitle.ifBlank { stripped },
            year = year,
            isSeries = isSeries,
            season = season,
            episode = episode,
            quality = quality
        )
    }

    /** Map quality string. */
    private fun searchQuality(raw: String?): SearchQuality? {
        val s = raw ?: return null
        val u = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase()
        return when {
            Regex("\\b(4k|uhd|2160p)\\b").containsMatchIn(u) -> SearchQuality.FourK
            Regex("\\b(1080p|fullhd)\\b").containsMatchIn(u) -> SearchQuality.HD
            Regex("\\b(720p)\\b").containsMatchIn(u) -> SearchQuality.SD
            Regex("\\b(bluray|bdrip)\\b").containsMatchIn(u) -> SearchQuality.BlueRay
            Regex("\\b(web[- ]?dl|webrip|webdl)\\b").containsMatchIn(u) -> SearchQuality.WebRip
            else -> null
        }
    }

    /** Infer TvType. */
    private fun getTvType(parsed: ParsedTitle): TvType {
        val lower = parsed.title.lowercase()
        if (lower.contains("anime") || lower.contains("ova") || lower.contains("donghua")) {
            return if (parsed.isSeries) TvType.Anime else TvType.Anime
        }
        return if (parsed.isSeries) TvType.TvSeries else TvType.Movie
    }


    internal data class TmdbMeta(
        val tmdbId: Int?,
        val title: String?,
        val overview: String?,
        val poster: String?,
        val backdrop: String?,
        val year: Int?,
        val cast: List<ActorData>,
        val imdbId: String?
    )

    /** Search TMDB. */
    private suspend fun tmdbSearch(parsed: ParsedTitle): TmdbMeta? {
        if (parsed.title.isBlank()) return null
        val type = if (parsed.isSeries) "tv" else "movie"
        val query = URLEncoder.encode(parsed.title, "UTF-8")
        val yearParam = parsed.year?.let { "&year=$it" } ?: ""

        val json = try {
            JSONObject(
                app.get("$TMDB_API/search/$type?api_key=$TMDB_API_KEY&query=$query$yearParam").text
            )
        } catch (e: Exception) {
            Log.w(TAG, "TMDB search failed: ${e.message}")
            return null
        }

        val first = json.optJSONArray("results")?.optJSONObject(0) ?: return null
        val tmdbId = first.optInt("id", -1).takeIf { it > 0 }
        val title = first.optString("name").ifBlank { first.optString("title") }.ifBlank { null }
        val overview = first.optString("overview").ifBlank { null }
        val poster = first.optString("poster_path").takeIf { it.isNotBlank() }?.let { "$TMDB_IMG$it" }
        val backdrop = first.optString("backdrop_path").takeIf { it.isNotBlank() }?.let { "$TMDB_IMG$it" }
        val dateStr = first.optString("first_air_date").ifBlank { first.optString("release_date") }
        val year = dateStr.take(4).toIntOrNull()

        var cast = emptyList<ActorData>()
        var imdbId: String? = null
        if (tmdbId != null) {
            try {
                val detail = JSONObject(
                    app.get("$TMDB_API/$type/$tmdbId?api_key=$TMDB_API_KEY&append_to_response=credits,external_ids").text
                )
                val castArr = detail.optJSONObject("credits")?.optJSONArray("cast")
                if (castArr != null) {
                    cast = (0 until castArr.length()).mapNotNull { i ->
                        val c = castArr.optJSONObject(i) ?: return@mapNotNull null
                        val name = c.optString("name").ifBlank { c.optString("original_name") }.ifBlank { return@mapNotNull null }
                        val profile = c.optString("profile_path").takeIf { it.isNotBlank() }?.let { "$TMDB_IMG$it" }
                        val character = c.optString("character").ifBlank { null }
                        ActorData(Actor(name, profile), roleString = character)
                    }
                }
                imdbId = detail.optJSONObject("external_ids")?.optString("imdb_id")?.ifBlank { null }
            } catch (e: Exception) {
                Log.w(TAG, "TMDB detail fetch failed: ${e.message}")
            }
        }

        return TmdbMeta(tmdbId, title, overview, poster, backdrop, year, cast, imdbId)
    }


    override val mainPage = mainPageOf(
        Pair("latest", "Latest Links")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            when (request.data) {
                else -> {
                    val json = getJson("/api/links?q=")
                    val res = tryParseJson<MkVSearchResponse>(json)
                    val items = groupAndMapLinks(res?.results.orEmpty())
                    newHomePageResponse(
                        HomePageList(request.name, items, isHorizontalImages = false),
                        hasNext = false
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage(${request.name}) failed: ${e.message}")
            newHomePageResponse(
                HomePageList(request.name, emptyList(), isHorizontalImages = false),
                hasNext = false
            )
        }
    }

    private suspend fun groupAndMapLinks(results: List<MkVLink>): List<SearchResponse> {
        val filtered = results.filterNot { link ->
            Regex("\\.(zip|rar|7z|tar)(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(link.title)
        }
        if (filtered.isEmpty()) return emptyList()

        val groups = filtered.groupBy { link ->
            val parsed = parseTitle(link.title)
            Pair(parsed.title.lowercase().trim(), parsed.year)
        }

        return groups.entries.toList().amap { entry ->
            val (key, groupLinks) = entry
            val firstLink = groupLinks.first()
            val parsed = parseTitle(firstLink.title)

            val tmdb = tmdbSearch(parsed)
            val displayTitle = tmdb?.title ?: parsed.title
            val displayYear = tmdb?.year ?: parsed.year

            val finalTitle = buildString {
                append(displayTitle)
                if (displayYear != null) append(" ($displayYear)")
            }

            val sortedLinks = groupLinks.sortedByDescending { link ->
                val q = parseTitle(link.title).quality?.lowercase().orEmpty()
                when {
                    "2160p" in q || "4k" in q -> 4
                    "1080p" in q -> 3
                    "720p" in q -> 2
                    "480p" in q -> 1
                    else -> 0
                }
            }

            val payload = MkVGroupPayload(
                title = displayTitle,
                year = displayYear,
                links = sortedLinks
            ).toJson()

            newMovieSearchResponse(
                finalTitle,
                "$mainUrl/group?d=" + URLEncoder.encode(payload, "UTF-8"),
                getTvType(parsed)
            ) {
                this.posterUrl = tmdb?.poster
                this.quality = searchQuality(parsed.quality ?: sortedLinks.firstNotNullOfOrNull { parseTitle(it.title).quality })
            }
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (query.isBlank()) return emptyList<SearchResponse>().toNewSearchResponseList()
        return try {
            val json = getJson("/api/links?q=${URLEncoder.encode(query, "UTF-8")}")
            val res = tryParseJson<MkVSearchResponse>(json)
            val items = groupAndMapLinks(res?.results.orEmpty())
            items.toNewSearchResponseList()
        } catch (e: Exception) {
            Log.e(TAG, "search('$query') failed: ${e.message}")
            emptyList<SearchResponse>().toNewSearchResponseList()
        }
    }

    /** Decode a `link/<id>?d=<json>` URL back into a MkVLink. */
    private fun decodeInternalUrl(url: String): MkVLink? {
        return try {
            val encoded = url.substringAfter("d=", "").substringBefore('&')
            if (encoded.isBlank()) return null
            tryParseJson<MkVLink>(java.net.URLDecoder.decode(encoded, "UTF-8"))
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun buildLoadResponse(
        url: String, 
        baseTitle: String, 
        baseYear: Int?, 
        links: List<MkVLink>, 
        tmdbMeta: TmdbMeta? = null
    ): LoadResponse {
        val linkParsedList = links.map { it to parseTitle(it.title) }
        val isTvSeries = linkParsedList.any { it.second.season != null || it.second.episode != null }

        val tmdb = tmdbMeta ?: tmdbSearch(ParsedTitle(baseTitle, baseYear, isTvSeries, null, null, null))
        val displayTitle = tmdb?.title ?: baseTitle
        val plotStr = tmdb?.overview

        if (isTvSeries) {
            // Group by Season/Episode
            val epsBySnE = linkParsedList.groupBy { Pair(it.second.season ?: 1, it.second.episode ?: 0) }
            val episodes = epsBySnE.map { (snE, linksForEp) ->
                val (s, e) = snE
                val epPayload = MkVGroupPayload(
                    title = displayTitle,
                    year = baseYear,
                    links = linksForEp.map { it.first }
                ).toJson()

                newEpisode(epPayload) {
                    this.season = s
                    this.episode = e
                    this.name = "Episode $e"
                    this.posterUrl = tmdb?.poster
                    val qualities = linksForEp.mapNotNull { it.second.quality }.distinct().joinToString(", ")
                    if (qualities.isNotBlank()) {
                        this.description = "Available Qualities: $qualities"
                    }
                }
            }

            return newTvSeriesLoadResponse(displayTitle, url, TvType.TvSeries, episodes) {
                this.plot = plotStr
                this.posterUrl = tmdb?.poster
                this.backgroundPosterUrl = tmdb?.backdrop
                this.year = tmdb?.year ?: baseYear
                this.actors = tmdb?.cast ?: emptyList()
                tmdb?.tmdbId?.let { addTMDbId(it.toString()) }
                tmdb?.imdbId?.let { addImdbUrl(it) }
            }
        } else {
            // Movie - pass the whole group payload as dataUrl
            val moviePayload = MkVGroupPayload(
                title = displayTitle,
                year = baseYear,
                links = links
            ).toJson()
            
            return newMovieLoadResponse(displayTitle, url, TvType.Movie, moviePayload) {
                this.plot = plotStr
                this.posterUrl = tmdb?.poster
                this.backgroundPosterUrl = tmdb?.backdrop
                this.year = tmdb?.year ?: baseYear
                this.actors = tmdb?.cast ?: emptyList()
                tmdb?.tmdbId?.let { addTMDbId(it.toString()) }
                tmdb?.imdbId?.let { addImdbUrl(it) }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.startsWith("$mainUrl/group?")) {
            val encodedPayload = url.substringAfter("d=").substringBefore('&')
            val payload = tryParseJson<MkVGroupPayload>(java.net.URLDecoder.decode(encodedPayload, "UTF-8"))
                ?: return newMovieLoadResponse("Invalid group", url, TvType.Movie, "") { this.plot = "Could not decode payload." }

            return buildLoadResponse(url, payload.title, payload.year, payload.links)
        }

        // Fallback for direct links
        val link = decodeInternalUrl(url) ?: return newMovieLoadResponse(
            "Invalid link", url, TvType.Movie, ""
        ) { this.plot = "Could not decode MkVBase payload." }

        val parsed = parseTitle(link.title)
        val tvType = getTvType(parsed)
        val tmdb = tmdbSearch(parsed)
        val payload = MkVEpisodePayload(
            url = link.url,
            host = hostOf(link.url),
            title = stripSourcePrefix(link.title)
        ).toJson()

        val title = tmdb?.title ?: parsed.title
        val plotStr = tmdb?.overview

        if (tvType == TvType.TvSeries) {
            val ep = newEpisode(payload) {
                this.season = parsed.season ?: 1
                this.episode = parsed.episode ?: 1
                this.name = "Episode ${this.episode}"
                this.posterUrl = tmdb?.poster
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, listOf(ep)) {
                this.plot = plotStr
                this.posterUrl = tmdb?.poster
                this.backgroundPosterUrl = tmdb?.backdrop
                this.year = tmdb?.year ?: parsed.year
                this.actors = tmdb?.cast ?: emptyList()
                tmdb?.tmdbId?.let { addTMDbId(it.toString()) }
                tmdb?.imdbId?.let { addImdbUrl(it) }
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, payload) {
                this.plot = plotStr
                this.posterUrl = tmdb?.poster
                this.backgroundPosterUrl = tmdb?.backdrop
                this.year = tmdb?.year ?: parsed.year
                this.actors = tmdb?.cast ?: emptyList()
                tmdb?.tmdbId?.let { addTMDbId(it.toString()) }
                tmdb?.imdbId?.let { addImdbUrl(it) }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        // Try parsing as MkVGroupPayload first (from Movie or TvSeries episode)
        val groupPayload = tryParseJson<MkVGroupPayload>(data)
        if (groupPayload != null && groupPayload.links.isNotEmpty()) {
            groupPayload.links.amap { link ->
                val parsed = parseTitle(link.title)
                val qualityStr = parsed.quality ?: "Unknown"
                
                val foundLinks = mutableListOf<ExtractorLink>()
                loadExtractor(link.url, "$mainUrl/", subtitleCallback) { extLink ->
                    foundLinks.add(extLink)
                }
                for (extLink in foundLinks) {
                    callback(
                        newExtractorLink(
                            extLink.source,
                            "$qualityStr - ${extLink.name}",
                            extLink.url,
                            if (extLink.isM3u8) com.lagradost.cloudstream3.utils.ExtractorLinkType.M3U8 else if (extLink.isDash) com.lagradost.cloudstream3.utils.ExtractorLinkType.DASH else com.lagradost.cloudstream3.utils.ExtractorLinkType.VIDEO
                        ) {
                            this.referer = extLink.referer
                            this.quality = extLink.quality
                            this.headers = extLink.headers
                            this.extractorData = extLink.extractorData
                        }
                    )
                }
            }
            return true
        }

        // Fallback for older MkVEpisodePayload or single direct links
        val oldPayload = tryParseJson<MkVEpisodePayload>(data)
        if (oldPayload != null && oldPayload.url.isNotBlank()) {
            return try {
                loadExtractor(oldPayload.url, "$mainUrl/", subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(TAG, "loadExtractor failed for ${oldPayload.url}: ${e.message}")
                false
            }
        }

        return false
    }
}
