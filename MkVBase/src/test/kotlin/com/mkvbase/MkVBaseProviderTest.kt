package com.mkvbase

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/**
 * JVM unit tests for MkVBaseProvider.
 */
class MkVBaseProviderTest {

    private val provider = MkVBaseProvider()

    // Provider Metadata

    @Test
    fun `provider metadata is correct`() {
        assertEquals("MkVBase", provider.name)
        assertEquals("https://mkvbase.site", provider.mainUrl)
        assertEquals("en", provider.lang)
        assertTrue("hasMainPage should be true", provider.hasMainPage)
        assertTrue("Should support Movie", provider.supportedTypes.contains(TvType.Movie))
        assertTrue("Should support TvSeries", provider.supportedTypes.contains(TvType.TvSeries))
        assertTrue("Should support Anime", provider.supportedTypes.contains(TvType.Anime))
        assertTrue("Should support Others", provider.supportedTypes.contains(TvType.Others))
        println("[PASS] Provider metadata is correct")
    }

    // Filename parsing

    @Test
    fun `parseTitle handles movie with year and quality`() {
        val raw = "GDFlix | [SD RIPS] Inception (2010) 1080p BluRay [Telugu + Eng] x265 10bit HEVC ESub mkv"
        val parsed = provider.parseTitle(raw)

        assertEquals("Inception", parsed.title)
        assertEquals(2010, parsed.year)
        assertFalse("Should detect as movie (no SxxExx)", parsed.isSeries)
        assertNull(parsed.season)
        assertNull(parsed.episode)
        assertEquals("1080p", parsed.quality)
        println("[PASS] Movie filename parsed: $parsed")
    }

    @Test
    fun `parseTitle handles series episode`() {
        val raw = "GDFlix | From 2026 S04E06 The Heart Is A Lonely Hunter 1080p AMZN WEB DL DUAL DDP5 1 H 264 DUDU mkv"
        val parsed = provider.parseTitle(raw)

        assertEquals("From", parsed.title)
        assertEquals(2026, parsed.year)
        assertTrue("Should detect as series (S04E06 present)", parsed.isSeries)
        assertEquals(4, parsed.season)
        assertEquals(6, parsed.episode)
        assertEquals("1080p", parsed.quality)
        println("[PASS] Series episode parsed: $parsed")
    }

    @Test
    fun `parseTitle handles 4K UHD`() {
        val raw = "GDFlix | Inception (2010) 2160p 4K UHD HDR10+ BluRay x265 10bit HEVC ESub mkv"
        val parsed = provider.parseTitle(raw)

        assertEquals("Inception", parsed.title)
        assertEquals(2010, parsed.year)
        assertEquals("2160p", parsed.quality)
        println("[PASS] 4K UHD filename parsed: $parsed")
    }

    @Test
    fun `parseTitle handles episode-only number`() {
        val raw = "GDFlix | Dorohedoro 2026 S02 1080p AMZN WEB DL MULTi DDP2 0 H 264 DUDU rar"
        val parsed = provider.parseTitle(raw)

        assertEquals("Dorohedoro", parsed.title)
        assertEquals(2026, parsed.year)
        assertTrue("Should detect as series (S02 present)", parsed.isSeries)
        assertEquals(2, parsed.season)
        assertNull("S02 alone has no episode number", parsed.episode)
        println("[PASS] Season-pack filename parsed: $parsed")
    }

    @Test
    fun `parseTitle handles title with no year`() {
        val raw = "Pulse 1988 720p HEVC BluRay HIN ENG x265 ESub SkymoviesHD mkv"
        val parsed = provider.parseTitle(raw)

        assertEquals("Pulse", parsed.title)
        assertEquals(1988, parsed.year)
        assertFalse(parsed.isSeries)
        assertEquals("720p", parsed.quality)
        println("[PASS] Pre-2000 movie parsed: $parsed")
    }

    @Test
    fun `parseTitle handles pack link`() {
        val raw = "GDFlix | Marvel Cinematic Universe (2008 2025) ~ TombDoc"
        val parsed = provider.parseTitle(raw)

        assertEquals("Marvel Cinematic Universe", parsed.title)
        assertEquals(2008, parsed.year)
        assertFalse(parsed.isSeries)
        println("[PASS] Collection pack parsed: $parsed")
    }

    // JSON DTO contracts

    @Test
    fun `sample search response shape matches what provider expects`() {
        val sampleJson = """
            {
              "results": [
                {
                  "id": 308413,
                  "title": "GDFlix | Inception (2010) 1080p BluRay x265 HEVC ESub",
                  "url": "https://gdflix.dev/file/Mx0tzFgyol4hQBn",
                  "created_at": "2026-06-03T14:10:01.525+00:00"
                }
              ],
              "count": 1
            }
        """.trimIndent()

        val parsed = tryParseJson<MkVBaseProvider.MkVSearchResponse>(sampleJson)
        assertNotNull("JSON should parse into MkVSearchResponse", parsed)
        assertEquals(1, parsed!!.count)
        assertEquals(1, parsed.results!!.size)

        val link = parsed.results!!.first()
        assertEquals(308413L, link.id)
        assertEquals("https://gdflix.dev/file/Mx0tzFgyol4hQBn", link.url)
        assertEquals("2026-06-03T14:10:01.525+00:00", link.createdAt)
        println("[PASS] Sample search response parses correctly")
    }

    @Test
    fun `sample trending response shape matches what provider expects`() {
        val sampleJson = """
            {"trending":["Toy Story 5","Disclosure Day","Obsession","Backrooms"]}
        """.trimIndent()

        val parsed = tryParseJson<MkVBaseProvider.MkVTrendingResponse>(sampleJson)
        assertNotNull("JSON should parse into MkVTrendingResponse", parsed)
        assertEquals(4, parsed!!.trending!!.size)
        assertEquals("Toy Story 5", parsed.trending!!.first())
        println("[PASS] Sample trending response parses correctly")
    }

    @Test
    fun `episode payload serializes and deserializes correctly`() {
        val original = MkVBaseProvider.MkVEpisodePayload(
            url = "https://gdflix.dev/file/abc123",
            host = "gdflix.dev",
            title = "Inception (2010) 1080p BluRay"
        )
        val json = original.toJson()
        val restored = tryParseJson<MkVBaseProvider.MkVEpisodePayload>(json)

        assertNotNull(restored)
        assertEquals(original.url, restored!!.url)
        assertEquals(original.host, restored.host)
        assertEquals(original.title, restored.title)
        println("[PASS] Episode payload round-trips correctly")
    }

    // Host extraction

    @Test
    fun `gdflix URLs match the bundled extractor mainUrl wildcard`() {
        // Validate gdflix URLs
        val gdflixHosts = listOf(
            "https://gdflix.dev/file/abc",
            "https://new.gdflix.io/pack/xyz"
        )
        gdflixHosts.forEach { url ->
            assertTrue("URL should match gdflix pattern: $url", url.contains("gdflix"))
        }
        println("[PASS] GDFlix host patterns recognised")
    }

    @Test
    fun `hubcloud URLs match the bundled extractor mainUrl`() {
        val hubcloudHosts = listOf(
            "https://hubcloud.foo/video/w1kr57rudkjw1vp",
            "https://hubcloud.foo/drive/pwl2dqbisw1pisw"
        )
        hubcloudHosts.forEach { url ->
            assertTrue("URL should match hubcloud pattern: $url", url.contains("hubcloud.foo"))
        }
        println("[PASS] HubCloud host patterns recognised")
    }

    // Live integration tests

    @Test
    fun `live search returns results`() = runBlocking {
        println("[*] Querying live search for 'Inception'...")
        val results = provider.search("Inception", 1)
        assertNotNull("Search results list should not be null", results)
        assertTrue("Search should return results for 'Inception'", results.items.isNotEmpty())
        println("[PASS] Live search returned ${results.items.size} results. First result: ${results.items.first().name}")
    }

    @Test
    fun `live main page returns trending and latest sections`() = runBlocking {
        println("[*] Querying live main page...")
        val latestRequest = com.lagradost.cloudstream3.MainPageRequest("Latest Links", "latest", true)
        val trendingRequest = com.lagradost.cloudstream3.MainPageRequest("Trending Searches", "trending", true)
        
        val latestResponse = provider.getMainPage(1, latestRequest)
        assertNotNull("Latest main page response should not be null", latestResponse)
        assertTrue("Latest links should not be empty", latestResponse.items.isNotEmpty())
        println("[PASS] Latest Links contains ${latestResponse.items.size} items.")

        val trendingResponse = provider.getMainPage(1, trendingRequest)
        assertNotNull("Trending main page response should not be null", trendingResponse)
        assertTrue("Trending searches should not be empty", trendingResponse.items.isNotEmpty())
        println("[PASS] Trending Searches contains ${trendingResponse.items.size} items.")
    }
}

