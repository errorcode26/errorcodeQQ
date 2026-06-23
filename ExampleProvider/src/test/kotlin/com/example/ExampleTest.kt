package com.example

import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

class ExampleTest {
    private val provider = ExampleProvider()

    // Replace with a valid URL from the provider
    private val testUrl = "https://example.com/movie-page"

    @Test
    fun testProviderMetadata() = runBlocking {
        println("[*] Loading URL: $testUrl")
        val response = provider.load(testUrl)

        // 1. Verify Core Details
        assert(response.name.isNotBlank()) { "Error: Title card is blank!" }
        assert(response.posterUrl?.startsWith("http") == true || response.posterUrl.isNullOrEmpty()) { "Error: Poster URL is invalid: ${response.posterUrl}" }

        println("Title: ${response.name}")
        println("Poster: ${response.posterUrl}")
        
        // 2. Verify Episode Indexing
        val episodes = if (response is TvSeriesLoadResponse) {
            response.episodes
        } else emptyList()
        
        if (response is TvSeriesLoadResponse) {
            assert(episodes.isNotEmpty()) { "Error: Mapped episode list is empty!" }
            println("Total Mapped Episodes: ${episodes.size}")
            
            // 3. Verify Individual Episode Integrity
            episodes.forEachIndexed { index, ep ->
                val epNum = ep.episode ?: 0
                assert(ep.name?.isNotBlank() == true || epNum > 0) { "Error: Episode $epNum is missing details!" }
                assert(ep.data.isNotBlank()) { "Error: Playback payload for Episode $epNum is empty!" }
            }
        }
    }

    @Test
    fun testVideoStreamIntegrity() = runBlocking {
        println("[*] Fetching and verifying playback stream links directly...")
        val response = provider.load(testUrl)
        val epData = if (response is TvSeriesLoadResponse) {
            response.episodes.firstOrNull()?.data
        } else if (response is com.lagradost.cloudstream3.MovieLoadResponse) {
            response.dataUrl
        } else null
        
        assert(epData != null) { "Error: No episode/movie data payload found!" }
        
        val resolvedLinks = mutableListOf<ExtractorLink>()
        provider.loadLinks(epData!!, isCasting = false, subtitleCallback = {}, callback = { link ->
            resolvedLinks.add(link)
        })
        
        println("[*] Resolved ${resolvedLinks.size} links.")
    }
}
