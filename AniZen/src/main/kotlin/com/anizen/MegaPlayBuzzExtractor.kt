package com.anizen

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import android.util.Log

/**
 * Extractor for MegaPlay.buzz embed URLs.
 */
open class MegaPlayBuzzExtractor : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    companion object {
        private const val TAG = "MegaPlayBuzz"
        private val cfInterceptor = CloudflareKiller()
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "Referer" to (referer ?: "https://anizen.tr/"),
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        try {
            var response = app.get(url, headers = headers)

            // Handle Cloudflare challenge
            if (response.document.select("title").text().trim() == "Just a moment") {
                Log.d(TAG, "CF challenge on $url — invoking CloudflareKiller")
                response = app.get(url, headers = headers, interceptor = cfInterceptor)
            }

            val html = response.text

            // Config patterns (JWPlayer, Video.js)
            val filePatterns = listOf(
                Regex("""["']file["']\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""source\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""src\s*:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                Regex("""url\s*:\s*["']([^"']+\.m3u8[^"']*)["']""")
            )

            for (pattern in filePatterns) {
                val match = pattern.find(html)
                if (match != null) {
                    val m3u8Url = match.groupValues[1]
                    Log.d(TAG, "Found m3u8 via config pattern: $m3u8Url")
                    callback.invoke(
                        newExtractorLink(name, "$name [Sub/Dub]", m3u8Url) {
                            this.quality = Qualities.Unknown.value
                            this.referer = url
                        }
                    )
                    return
                }
            }

            // HTML5 source tag
            val sourceTag = response.document.selectFirst("source[src]")
            if (sourceTag != null) {
                val src = sourceTag.attr("src")
                if (src.contains(".m3u8") || src.contains("video")) {
                    val fullUrl = if (src.startsWith("http")) src else "$mainUrl$src"
                    Log.d(TAG, "Found source tag: $fullUrl")
                    callback.invoke(
                        newExtractorLink(name, "$name [Sub/Dub]", fullUrl) {
                            this.quality = Qualities.Unknown.value
                            this.referer = url
                        }
                    )
                    return
                }
            }

            // Bare m3u8 links
            val m3u8Pattern = Regex("""(https?://[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
            val m3u8Matches = m3u8Pattern.findAll(html).toList()
            if (m3u8Matches.isNotEmpty()) {
                for (match in m3u8Matches) {
                    val m3u8Url = match.groupValues[1]
                    Log.d(TAG, "Found bare m3u8: $m3u8Url")
                    callback.invoke(
                        newExtractorLink(name, "$name [Sub/Dub]", m3u8Url) {
                            this.quality = Qualities.Unknown.value
                            this.referer = url
                        }
                    )
                }
                return
            }

            // API request pattern
            val apiPattern = Regex("""(?:ajax|api|source|embed)\s*[=:]\s*["']([^"']+)["']""")
            for (match in apiPattern.findAll(html)) {
                val apiPath = match.groupValues[1]
                if (apiPath.isNotEmpty()) {
                    try {
                        val apiUrl = if (apiPath.startsWith("http")) apiPath else "$mainUrl$apiPath"
                        val apiResp = app.get(apiUrl, headers = headers + ("Referer" to url))
                        val apiText = apiResp.text

                        // Search in API response
                        val apiM3u8 = m3u8Pattern.find(apiText)
                        if (apiM3u8 != null) {
                            val m3u8Url = apiM3u8.groupValues[1]
                            Log.d(TAG, "Found m3u8 via API: $m3u8Url")
                            callback.invoke(
                                newExtractorLink(name, "$name [Sub/Dub]", m3u8Url) {
                                    this.quality = Qualities.Unknown.value
                                    this.referer = url
                                }
                            )
                            return
                        }

                        // Parse JSON response
                        if (apiText.trimStart().startsWith("{")) {
                            try {
                                val jsonData = parseJson<Map<String, Any?>>(apiText)
                                val source = jsonData["source"] as? String
                                    ?: jsonData["file"] as? String
                                    ?: jsonData["url"] as? String
                                    ?: (jsonData["data"] as? Map<String, Any?>)?.let { d ->
                                        d["source"] as? String ?: d["url"] as? String ?: d["file"] as? String
                                    }
                                if (!source.isNullOrEmpty()) {
                                    Log.d(TAG, "Found source via JSON API: $source")
                                    callback.invoke(
                                        newExtractorLink(name, "$name [Sub/Dub]", source) {
                                            this.quality = Qualities.Unknown.value
                                            this.referer = url
                                        }
                                    )
                                    return
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "API path $apiPath failed: ${e.message}")
                    }
                }
            }

            // Nested iframe HLS
            val nestedIframe = response.document.selectFirst("iframe[src]")
            if (nestedIframe != null) {
                val nestedSrc = nestedIframe.attr("src")
                if (nestedSrc.isNotEmpty() && nestedSrc != url) {
                    Log.d(TAG, "Following nested iframe: $nestedSrc")
                    val nestedUrl = if (nestedSrc.startsWith("http")) nestedSrc else "$mainUrl$nestedSrc"
                    getUrl(nestedUrl, referer ?: url, subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract from $url: ${e.message}")
        }
    }
}
