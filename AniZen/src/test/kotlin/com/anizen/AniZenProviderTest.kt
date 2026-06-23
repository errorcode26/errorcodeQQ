package com.anizen

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.AnimeSearchResponse

/**
 * JVM unit tests for AniZenProvider.
 */
class AniZenProviderTest {
    private val provider = AniZenProvider()
    private val testAnimeUrl = "https://anizen.tr/watch/bleach-yaa9n"
    private val testMovieUrl = "https://anizen.tr/watch/bleach-the-movie-fade-to-black-hudbv"

    // ── Provider Metadata Tests ────────────────────────────────────────────

    @Test
    fun testProviderMetadata() {
        assert(provider.name == "AniZen") { "Provider name mismatch" }
        assert(provider.mainUrl == "https://anizen.tr") { "Main URL mismatch" }
        assert(provider.lang == "en") { "Language mismatch" }
        assert(provider.hasMainPage) { "hasMainPage should be true" }
        assert(provider.supportedTypes.contains(TvType.Anime)) { "Should support Anime type" }
        assert(provider.supportedTypes.contains(TvType.AnimeMovie)) { "Should support AnimeMovie type" }
        println("[PASS] Provider metadata is correct")
    }

    // ── RSC Parsing Tests ──────────────────────────────────────────────────

    @Test
    fun testRscPushExtraction() {
        val sampleHtml = """
            <html><body>
            <script>self.__next_f.push([1,"{\"id\":\"test-anime-abc123\",\"title\":\"Test Anime\",\"cover\":\"https://cdn.anizen.tr/poster/test.jpg\"}"])</script>
            <script>self.__next_f.push([1,"other data"])</script>
            </body></html>
        """.trimIndent()

        val pushes = provider.extractRscPushes(sampleHtml)
        assert(pushes.size == 2) { "Expected 2 pushes, got ${pushes.size}" }
        assert(pushes[0].contains("test-anime-abc123")) { "First push should contain anime ID" }
        println("[PASS] RSC push extraction works correctly")
    }

    @Test
    fun testAnimeItemParsing() {
        val samplePush = """
            {"id":"bleach-yaa9n","dataId":"yaa9n","title":"Bleach","titleJP":"Bleach","cover":"https://cdn.anizen.tr/poster/bleach-yaa9n.jpg","rating":7.8,"views":1570,"description":"Ichigo Kurosaki is an ordinary high schooler","genres":["Action","Adventure","Comedy"],"type":"TV","duration":"24m min","totalEpisodes":366,"status":"Finished Airing","premiered":"FALL 2004","studio":"Studio-Pierrot","subCount":366,"dubCount":366,"contentRating":"PG-13"}
        """

        val items = provider.parseAnimeItemsFromRsc(samplePush)
        assert(items.isNotEmpty()) { "Should parse at least one anime item" }

        val item = items.first()
        assert(item.id == "bleach-yaa9n") { "ID mismatch: ${item.id}" }
        assert(item.title == "Bleach") { "Title mismatch: ${item.title}" }
        assert(item.rating == 7.8) { "Rating mismatch: ${item.rating}" }
        assert(item.views == 1570) { "Views mismatch: ${item.views}" }
        assert(item.totalEpisodes != null) { "totalEpisodes should not be null" }
        println("[PASS] Anime item parsing works correctly")
    }

    @Test
    fun testTvTypeClassification() {
        // TV series
        val tvItem = provider.RscAnimeItem(
            id = "test-tv",
            title = "Test Anime",
            type = "TV",
            totalEpisodes = 24
        )
        assert(provider.getTvType(tvItem) == TvType.Anime) { "TV type should be Anime" }

        // Movie
        val movieItem = provider.RscAnimeItem(
            id = "test-movie",
            title = "Test Movie",
            type = "Movie",
            totalEpisodes = 1
        )
        assert(provider.getTvType(movieItem) == TvType.AnimeMovie) { "Movie type should be AnimeMovie" }

        // Single episode fallback
        val singleEpItem = provider.RscAnimeItem(
            id = "test-single",
            title = "Test Special",
            totalEpisodes = 1
        )
        assert(provider.getTvType(singleEpItem) == TvType.AnimeMovie) { "Single episode should default to AnimeMovie" }

        println("[PASS] TV type classification works correctly")
    }

    @Test
    fun testEpisodeDataSerialization() {
        val epData = provider.AniZenEpisodeData(
            slug = "bleach-yaa9n",
            dataId = "yaa9n",
            episode = 5,
            streamType = "sub",
            streamKey = "servers:19596918",
            embedUrl = "https://megaplay.buzz/stream/s-2/13793/sub"
        )

        val json = epData.toJson()
        assert(json.contains("\"slug\":\"bleach-yaa9n\"")) { "JSON should contain slug" }
        assert(json.contains("\"episode\":5")) { "JSON should contain episode number" }
        assert(json.contains("\"streamType\":\"sub\"")) { "JSON should contain stream type" }

        // Parse back
        val parsed = provider.parseJson<provider.AniZenEpisodeData>(json)
        assert(parsed.slug == "bleach-yaa9n") { "Parsed slug mismatch" }
        assert(parsed.episode == 5) { "Parsed episode mismatch" }
        assert(parsed.streamType == "sub") { "Parsed streamType mismatch" }

        println("[PASS] Episode data serialization works correctly")
    }

    @Test
    fun testStringUnescaping() {
        val escaped = "Bleach the Movie: Fade to Black \\\"Kimi no Na wo Yobu\\\""
        val unescaped = escaped.unescapeRsc()
        assert(unescaped.contains("\"")) { "Should unescape quotes" }
        assert(!unescaped.contains("\\\"")) { "Should not contain escaped quotes" }

        val htmlEscaped = "Gold Roger &amp; the &#039;Pirate King&#039;"
        val clean = htmlEscaped.unescapeRsc()
        assert(clean.contains("&")) { "Should unescape &amp;" }
        assert(clean.contains("'")) { "Should unescape &#039;" }

        println("[PASS] String unescaping works correctly")
    }

    // ── Integration Tests (require network) ────────────────────────────────
    // These tests make real HTTP requests and may fail due to
    // Cloudflare or network issues. Uncomment to run manually.

    /*
    @Test
    fun testSearch() = runBlocking {
        val results = provider.search("Bleach")
        assert(results.isNotEmpty()) { "Search should return results" }
        println("Found ${results.size} search results for 'Bleach'")
        results.forEach { result ->
            println("  - ${result.name} (${result.url})")
        }
    }

    @Test
    fun testLoadAnimeDetail() = runBlocking {
        val response = provider.load(testAnimeUrl)
        assert(response.name.isNotBlank()) { "Title should not be blank" }
        assert(response.posterUrl?.startsWith("http") == true) { "Poster URL should be valid" }
        println("Loaded: ${response.name}")
        println("Poster: ${response.posterUrl}")
        println("Plot: ${response.plot?.take(100)}...")
    }

    @Test
    fun testLoadEpisodes() = runBlocking {
        val response = provider.load(testAnimeUrl)
        if (response is com.lagradost.cloudstream3.AnimeLoadResponse) {
            assert(response.episodes.isNotEmpty()) { "Episodes list should not be empty" }
            println("Total episodes: ${response.episodes.size}")
            response.episodes.take(5).forEach { ep ->
                println("  - Ep ${ep.episode}: ${ep.name}")
            }
        }
    }
    */
}
