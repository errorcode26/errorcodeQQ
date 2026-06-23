package com.anizen

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import java.net.URLDecoder

/**
 * Provider for AniZen (https://anizen.tr) using RSC data parsing.
 */
class AniZenProvider : MainAPI() {
    override var mainUrl = "https://anizen.tr"
    override var name = "AniZen"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false

    companion object {
        private const val TAG = "AniZen"
        private const val CDN_BASE = "https://cdn.anizen.tr"
        private val cfInterceptor = CloudflareKiller()
    }

    // Cloudflare-aware GET
    private suspend fun cfGet(url: String, referer: String? = null): NiceResponse {
        val headers = mutableMapOf<String, String>()
        if (referer != null) headers["Referer"] = referer

        var response = app.get(url, headers = headers)
        if (response.document.select("title").text().trim() == "Just a moment") {
            Log.d(TAG, "Cloudflare challenge detected for $url — invoking CloudflareKiller")
            response = app.get(url, headers = headers, interceptor = cfInterceptor)
        }
        return response
    }

    /** Extracts self.__next_f.push payload strings. */
    private fun extractRscPushes(html: String): List<String> {
        val pattern = Regex("""self\.__next_f\.push\(\[1,"(.*?)"\]\)""", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(html).map { it.groupValues[1] }.toList()
    }

    /** Parses JSON from RSC push. */
    private fun tryParseJson(input: String): Any? {
        try {
            return parseJson<Map<String, Any?>>(input)
        } catch (_: Exception) {}

        try {
            return parseJson<List<Any?>>(input)
        } catch (_: Exception) {}

        for (startChar in listOf('{', '[')) {
            val startIdx = input.indexOf(startChar)
            if (startIdx < 0) continue
            val substring = input.substring(startIdx)
            try {
                return if (startChar == '{') {
                    parseJson<Map<String, Any?>>(substring)
                } else {
                    parseJson<List<Any?>>(substring)
                }
            } catch (_: Exception) {
                val endIdx = findMatchingBracket(substring, startChar)
                if (endIdx > 0) {
                    try {
                        val balanced = substring.substring(0, endIdx + 1)
                        return if (startChar == '{') {
                            parseJson<Map<String, Any?>>(balanced)
                        } else {
                            parseJson<List<Any?>>(balanced)
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        return null
    }

    private fun findMatchingBracket(s: String, open: Char): Int {
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString = false
        var escape = false
        for ((i, c) in s.withIndex()) {
            if (escape) { escape = false; continue }
            if (c == '\\') { escape = true; continue }
            if (c == '"') { inString = !inString; continue }
            if (inString) continue
            if (c == open) depth++
            if (c == close) { depth--; if (depth == 0) return i }
        }
        return -1
    }

    /** Finds field value in RSC push. */
    private fun extractRscField(pushContent: String, fieldName: String): String? {
        val pattern = Regex(""""$fieldName"\s*:\s*"([^"]*?)" "")
        return pattern.find(pushContent)?.groupValues?.get(1)
    }

    /** Extracts array field from push string. */
    private fun extractRscArrayField(pushContent: String, fieldName: String): String? {
        val pattern = Regex(""""$fieldName"\s*:\s*(\[[^\]]*\])""")
        return pattern.find(pushContent)?.groupValues?.get(1)
    }

    /** Extracts numeric field from push string. */
    private fun extractRscNumberField(pushContent: String, fieldName: String): Double? {
        val pattern = Regex(""""$fieldName"\s*:\s*(-?\d+\.?\d*)""")
        return pattern.find(pushContent)?.groupValues?.get(1)?.toDoubleOrNull()
    }
    data class RscAnimeItem(
        val id: String,
        val dataId: String? = null,
        val title: String,
        val titleJP: String? = null,
        val cover: String? = null,
        val rating: Double? = null,
        val views: Int? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val tags: List<String>? = null,
        val type: String? = null,
        val duration: String? = null,
        val totalEpisodes: Any? = null,
        val status: String? = null,
        val premiered: String? = null,
        val studio: String? = null,
        val subCount: Int? = null,
        val dubCount: Int? = null,
        val contentRating: String? = null,
        val quality: String? = null,
        val rank: Int? = null,
        val episode: String? = null,
        val updatedAgo: String? = null,
        val aired: String? = null
    )

    /** Parses anime items from RSC. */
    private fun parseAnimeItemsFromRsc(pushContent: String): List<RscAnimeItem> {
        val items = mutableListOf<RscAnimeItem>()
        val animePattern = Regex(
            """\{"id":"([^"]+)"""" +
            """(?:,"dataId":"([^"]*)")?""" +
            ""","title":"([^"]*)"""" +
            """(?:"titleJP":"([^"]*)")?""" +
            ""","cover":"([^"]*)"""" +
            """(?:,"rating":([\d.]+))?""" +
            """(?:,"views":(\d+))?""" +
            """(?:,"description":"((?:[^"\\]|\\.)*)")?""" +
            """(?:,"genres":\[(.*?)\])?""" +
            """(?:,"tags":\[(.*?)\])?""" +
            """(?:,"type":"([^"]*)")?""" +
            """(?:,"duration":"([^"]*)")?""" +
            """(?:,"totalEpisodes":(?:"([^"]*)"|(\d+)))?""" +
            """(?:,"status":"([^"]*)")?""" +
            """(?:,"premiered":"([^"]*)")?""" +
            """(?:,"studio":"([^"]*)")?""" +
            """(?:,"subCount":(\d+))?""" +
            """(?:,"dubCount":(\d+))?"""
        )

        for (match in animePattern.findAll(pushContent)) {
            try {
                val item = RscAnimeItem(
                    id = match.groupValues[1],
                    dataId = match.groupValues[2].takeIf { it.isNotEmpty() },
                    title = match.groupValues[3].unescapeRsc(),
                    titleJP = match.groupValues[4].takeIf { it.isNotEmpty() }?.unescapeRsc(),
                    cover = match.groupValues[5].takeIf { it.isNotEmpty() }?.unescapeRsc(),
                    rating = match.groupValues[6].toDoubleOrNull(),
                    views = match.groupValues[7].toIntOrNull(),
                    description = match.groupValues[8].takeIf { it.isNotEmpty() }?.unescapeRsc(),
                    genres = match.groupValues[9].takeIf { it.isNotEmpty() }?.parseRscStringArray(),
                    tags = match.groupValues[10].takeIf { it.isNotEmpty() }?.parseRscStringArray(),
                    type = match.groupValues[11].takeIf { it.isNotEmpty() },
                    duration = match.groupValues[12].takeIf { it.isNotEmpty() },
                    totalEpisodes = match.groupValues[13].takeIf { it.isNotEmpty() }
                        ?: match.groupValues[14].toIntOrNull(),
                    status = match.groupValues[15].takeIf { it.isNotEmpty() },
                    premiered = match.groupValues[16].takeIf { it.isNotEmpty() },
                    studio = match.groupValues[17].takeIf { it.isNotEmpty() },
                    subCount = match.groupValues[18].toIntOrNull(),
                    dubCount = match.groupValues[19].toIntOrNull()
                )
                items.add(item)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse RSC anime item: ${e.message}")
            }
        }
        return items
    }

    private fun String.unescapeRsc(): String {
        return this
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&quot;", "\"")
            .replace("&#8217;", "'")
            .replace("&#8211;", "–")
            .replace("&#8220;", "\"")
            .replace("&#8221;", "\"")
            .trim()
    }

    private fun String.parseRscStringArray(): List<String>? {
        return try {
            val parsed = parseJson<List<String>>("[$this]")
            parsed
        } catch (_: Exception) {
            this.split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        }
    }

    /** Parses full anime detail from watch page RSC. */
    private fun parseAnimeDetailFromRsc(pushContent: String): RscAnimeItem? {
        val animeObjStart = pushContent.indexOf("\"anime\":{\"id\":")
        if (animeObjStart < 0) return null

        val substring = pushContent.substring(animeObjStart + 8)
        val items = parseAnimeItemsFromRsc(substring)
        return items.firstOrNull()
    }
    private fun getTvType(item: RscAnimeItem): TvType {
        val typeStr = item.type?.lowercase() ?: ""
        val titleLower = item.title.lowercase()

        return when {
            typeStr.contains("movie") || titleLower.contains("movie") -> TvType.AnimeMovie
            typeStr.contains("tv") || typeStr.contains("ona") || typeStr.contains("ova") ||
                typeStr.contains("special") -> TvType.Anime
            item.totalEpisodes != null && item.totalEpisodes.toString().toIntOrNull() == 1 -> TvType.AnimeMovie
            else -> TvType.Anime
        }
    }

    private fun inferTotalEpisodes(item: RscAnimeItem): Int {
        return when (val ep = item.totalEpisodes) {
            is Int -> ep
            is String -> ep.toIntOrNull() ?: 1
            else -> 1
        }
    }
    override val mainPage = mainPageOf(
        Pair("$mainUrl/home", "Trending"),
        Pair("$mainUrl/home#top-airing", "Top Airing"),
        Pair("$mainUrl/home#upcoming", "Upcoming"),
        Pair("$mainUrl/home#latest", "Latest Episodes")
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = cfGet(request.data)
        val html = response.text
        val pushes = extractRscPushes(html)

        val allAnimeItems = mutableListOf<RscAnimeItem>()
        for (push in pushes) {
            allAnimeItems.addAll(parseAnimeItemsFromRsc(push))
        }

        val seen = mutableSetOf<String>()
        val uniqueItems = allAnimeItems.filter { seen.add(it.id) }

        val filteredItems = when {
            request.data.contains("#top-airing") -> {
                uniqueItems.filter { it.rank != null }.sortedBy { it.rank }
            }
            request.data.contains("#upcoming") -> {
                uniqueItems.filter { it.status?.contains("Not Yet Aired", ignoreCase = true) == true }
                    .ifEmpty { uniqueItems.filter { it.rank != null }.take(20) }
            }
            request.data.contains("#latest") -> {
                uniqueItems.filter { it.episode != null }
                    .ifEmpty { uniqueItems.take(20) }
            }
            else -> {
                uniqueItems.sortedByDescending { it.views ?: 0 }.take(30)
            }
        }

        val list = filteredItems.map { item ->
            val url = "$mainUrl/watch/${item.id}"
            newAnimeSearchResponse(item.title, url, getTvType(item)) {
                this.posterUrl = item.cover
                this.subEpisodes = item.subCount
                this.dubEpisodes = item.dubCount
            }
        }

        return newHomePageResponse(request.name, list, hasNext = false)
    }
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val response = cfGet(searchUrl)
        val html = response.text
        val pushes = extractRscPushes(html)

        val items = mutableListOf<RscAnimeItem>()
        for (push in pushes) {
            items.addAll(parseAnimeItemsFromRsc(push))
        }
        val seen = mutableSetOf<String>()
        val uniqueItems = items.filter { seen.add(it.id) }

        return uniqueItems.map { item ->
            val url = "$mainUrl/watch/${item.id}"
            newAnimeSearchResponse(item.title, url, getTvType(item)) {
                this.posterUrl = item.cover
                this.subEpisodes = item.subCount
                this.dubEpisodes = item.dubCount
            }
        }
    }
    override suspend fun load(url: String): LoadResponse {
        val response = cfGet(url)
        val html = response.text
        val document = response.document
        val pushes = extractRscPushes(html)

        var animeDetail: RscAnimeItem? = null
        for (push in pushes) {
            animeDetail = parseAnimeDetailFromRsc(push)
            if (animeDetail != null) break
        }

        if (animeDetail == null) {
            val allItems = mutableListOf<RscAnimeItem>()
            for (push in pushes) {
                allItems.addAll(parseAnimeItemsFromRsc(push))
            }
            val slug = url.substringAfterLast("/")
            animeDetail = allItems.firstOrNull { it.id == slug }
        }

        if (animeDetail == null) {
            val title = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?: document.select("title").text()
            val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

            animeDetail = RscAnimeItem(
                id = url.substringAfterLast("/"),
                title = title.replace(" — Watch Anime Online", "").trim(),
                cover = poster,
                description = description,
                totalEpisodes = 1
            )
        }

        val item = animeDetail
        val title = item.title
        val tvType = getTvType(item)
        val totalEps = inferTotalEpisodes(item)
        val slug = item.id
        val dataId = item.dataId ?: item.id.substringAfterLast("-")

        val embedUrl = document.selectFirst("div[data-embed]")?.attr("data-embed")
        val streamKey = document.selectFirst("[data-stream-key]")?.attr("data-stream-key") ?: ""
        val defaultStreamType = document.selectFirst("[data-stream-type]")?.attr("data-stream-type") ?: "sub"

        val episodes = mutableListOf<Episode>()

        if (tvType == TvType.AnimeMovie && totalEps <= 1) {
            val epData = AniZenEpisodeData(
                slug = slug,
                dataId = dataId,
                episode = 1,
                streamType = defaultStreamType,
                streamKey = streamKey,
                embedUrl = embedUrl ?: ""
            ).toJson()

            episodes.add(
                Episode(epData, name = title, episode = 1, posterUrl = item.cover)
            )
        } else {
            val subCount = item.subCount ?: totalEps
            val dubCount = item.dubCount ?: 0

            for (ep in 1..subCount) {
                val epData = AniZenEpisodeData(
                    slug = slug,
                    dataId = dataId,
                    episode = ep,
                    streamType = "sub",
                    streamKey = streamKey,
                    embedUrl = if (ep == 1 && defaultStreamType == "sub") embedUrl else ""
                ).toJson()

                episodes.add(
                    Episode(epData, episode = ep, posterUrl = item.cover)
                )
            }

            if (dubCount > 0) {
                for (ep in 1..dubCount) {
                    val epData = AniZenEpisodeData(
                        slug = slug,
                        dataId = dataId,
                        episode = ep,
                        streamType = "dub",
                        streamKey = streamKey,
                        embedUrl = if (ep == 1 && defaultStreamType == "dub") embedUrl else ""
                    ).toJson()

                    episodes.add(
                        Episode(
                            epData,
                            name = "Dub - Ep $ep",
                            episode = ep,
                            posterUrl = item.cover
                        )
                    )
                }
            }
        }
        val related = mutableListOf<RscAnimeItem>()
        for (push in pushes) {
            related.addAll(parseAnimeItemsFromRsc(push))
        }
        val relatedResponses = related
            .filter { it.id != slug }
            .distinctBy { it.id }
            .take(10)
            .map { relItem ->
                newAnimeSearchResponse(relItem.title, "$mainUrl/watch/${relItem.id}", getTvType(relItem)) {
                    this.posterUrl = relItem.cover
                }
            }

        val ratingValue = item.rating

        return if (tvType == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, tvType, episodes.firstOrNull()?.data ?: "") {
                this.posterUrl = item.cover
                this.plot = item.description
                this.tags = item.genres ?: item.tags
                this.rating = ratingValue
                this.duration = item.duration
                this.recommendations = relatedResponses
                this.contentRating = item.contentRating
                this.year = item.premiered?.substringAfterLast(" ")?.toIntOrNull()
            }
        } else {
            newAnimeLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = item.cover
                this.plot = item.description
                this.tags = item.genres ?: item.tags
                this.rating = ratingValue
                this.duration = item.duration
                this.recommendations = relatedResponses
                this.contentRating = item.contentRating
                this.year = item.premiered?.substringAfterLast(" ")?.toIntOrNull()
                this.showStatus = when {
                    item.status?.contains("Currently Airing", ignoreCase = true) == true -> ShowStatus.Ongoing
                    item.status?.contains("Finished Airing", ignoreCase = true) == true -> ShowStatus.Completed
                    else -> null
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
        val epData = try {
            parseJson<AniZenEpisodeData>(data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse episode data: ${e.message}")
            return false
        }

        val slug = epData.slug
        val episode = epData.episode
        val streamType = epData.streamType

        // Use initial embed URL if present
        if (!epData.embedUrl.isNullOrEmpty()) {
            loadEmbedUrl(epData.embedUrl, streamType, callback)
        }

        // Fetch watch page for other episodes
        val watchUrl = "$mainUrl/watch/$slug"
        try {
            val response = cfGet(watchUrl)
            val html = response.text

            val doc = response.document
            val embedEl = doc.selectFirst("div[data-embed][data-episode=\"$episode\"][data-stream-type=\"$streamType\"]")
            if (embedEl != null) {
                val embed = embedEl.attr("data-embed")
                if (embed.isNotEmpty()) {
                    loadEmbedUrl(embed, streamType, callback)
                    return true
                }
            }

            val defaultEmbed = doc.selectFirst("div[data-embed]")?.attr("data-embed")
            if (!defaultEmbed.isNullOrEmpty() && episode == 1) {
                loadEmbedUrl(defaultEmbed, streamType, callback)
                return true
            }

            // Fetch servers from API
            try {
                val apiUrl = "$mainUrl/api/servers?id=$slug&episode=$episode"
                val apiResponse = cfGet(apiUrl)
                val apiHtml = apiResponse.text
                if (apiHtml.trimStart().startsWith("{") || apiHtml.trimStart().startsWith("[")) {
                    try {
                        val serversData = parseJson<Map<String, Any?>>(apiHtml)
                        extractServersFromApi(serversData, streamType, callback)
                    } catch (_: Exception) {
                        try {
                            val serversList = parseJson<List<Any?>>(apiHtml)
                            for (item in serversList) {
                                if (item is Map<*, *>) {
                                    extractServersFromApi(item.mapKeys { it.key.toString() }.mapValues { it.value }, streamType, callback)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "API servers endpoint failed: ${e.message}")
            }

            // Fallback: construct embed URL from stream key
            val streamKeyFromPage = doc.selectFirst("[data-stream-key]")?.attr("data-stream-key") ?: epData.streamKey
            if (streamKeyFromPage.isNotEmpty()) {
                val serverId = streamKeyFromPage.substringAfter("servers:").trim()
                if (serverId.isNotEmpty()) {
                    val constructedEmbed = "https://megaplay.buzz/stream/s-2/$serverId/$streamType"
                    loadEmbedUrl(constructedEmbed, streamType, callback)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to load links for $slug ep $episode: ${e.message}")
        }

        return true
    }

    /** Loads video links from embed URL. */
    private suspend fun loadEmbedUrl(
        embedUrl: String,
        streamType: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            loadExtractor(embedUrl, mainUrl, { _ -> }) { link ->
                callback.invoke(link)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Registered extractors failed for $embedUrl: ${e.message}")
        }

        try {
            val response = cfGet(embedUrl, referer = mainUrl)
            val html = response.text

            val configPatterns = listOf(
                Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""source\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            )
            for (pattern in configPatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    val label = "AniZen ${streamType.replaceFirstChar { it.uppercase() }}"
                    callback.invoke(
                        newExtractorLink(label, label, m3u8Url) {
                            this.quality = Qualities.Unknown.value
                            this.referer = embedUrl
                        }
                    )
                    return
                }
            }

            val m3u8Pattern = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
            for (match in m3u8Pattern.findAll(html)) {
                val m3u8Url = match.groupValues[1]
                val label = "AniZen ${streamType.replaceFirstChar { it.uppercase() }}"
                callback.invoke(
                    newExtractorLink(label, label, m3u8Url) {
                        this.quality = Qualities.Unknown.value
                        this.referer = embedUrl
                    }
                )
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract from embed URL $embedUrl: ${e.message}")
        }
    }

    /** Extracts servers from API response. */
    private fun extractServersFromApi(
        data: Map<String, Any?>,
        streamType: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val servers = data["servers"] as? List<Map<String, Any?>>
        servers?.forEach { server ->
            val url = server["url"] as? String ?: server["embed"] as? String ?: return@forEach
            val label = "AniZen ${streamType.replaceFirstChar { it.uppercase() }} - ${server["name"] ?: server["label"] ?: "Server"}"
            callback.invoke(
                newExtractorLink(label, label, url) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val dataField = data["data"] as? Map<String, Any?>
        if (dataField != null) {
            extractServersFromApi(dataField, streamType, callback)
        }

        val directUrl = data["url"] as? String ?: data["embed"] as? String ?: data["source"] as? String
        if (!directUrl.isNullOrEmpty() && (directUrl.contains(".m3u8") || directUrl.contains("stream"))) {
            val label = "AniZen ${streamType.replaceFirstChar { it.uppercase() }}"
            callback.invoke(
                newExtractorLink(label, label, directUrl) {
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
    data class AniZenEpisodeData(
        val slug: String,
        val dataId: String,
        val episode: Int,
        val streamType: String,
        val streamKey: String,
        val embedUrl: String?
    ) {
        fun toJson(): String {
            return """{"slug":"$slug","dataId":"$dataId","episode":$episode,"streamType":"$streamType","streamKey":"$streamKey","embedUrl":${if (embedUrl.isNullOrEmpty()) "null" else "\"$embedUrl\""}}"""
        }
    }
}
