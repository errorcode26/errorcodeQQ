package com.moviezwap

import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.MainPageRequest

class MoviezWapTest {
    private val provider = MoviezWapProvider()

    @Test
    fun testSearch() = runBlocking {
        println("[*] Testing Search query: 'Rescue Dawn'")
        val results = provider.search("Rescue Dawn")
        assert(results.isNotEmpty()) { "Search results are empty!" }
        results.forEach {
            println("  - Title: ${it.name} | URL: ${it.url} | Type: ${it.type} | Poster: ${it.posterUrl}")
        }
        assert(results.first().posterUrl?.startsWith("http") == true) { "Search result missing Cinemeta poster!" }
        println("[+] Search Test Passed. Found: ${results.size} matches.")
    }

    @Test
    fun testMainPage() = runBlocking {
        println("[*] Testing MainPage category: 'Telugu (2026) Movies'")
        val req = MainPageRequest("Telugu (2026) Movies", "category/Telugu-(2026)-Movies.html", false)
        val response = provider.getMainPage(1, req)
        val list = response.items.firstOrNull()?.list
        assert(list != null && list.isNotEmpty()) { "Main page category returned empty list!" }
        list?.forEach {
            println("  - Item: ${it.name} | Poster: ${it.posterUrl}")
        }
        println("[+] MainPage Test Passed. Found: ${list?.size} items.")
    }

    @Test
    fun testLoadAndLinks() = runBlocking {
        val testMovieUrl = "https://www.moviezwap.onl/movie/Rescue-Dawn-(2006)-Telugu-Dubbed-ORG.html"
        println("[*] Testing Load details for: $testMovieUrl")
        
        val response = provider.load(testMovieUrl)
        assert(response.name.isNotBlank()) { "Blank movie title parsed!" }
        assert(response.posterUrl?.startsWith("http") == true) { "Invalid poster url: ${response.posterUrl}" }
        
        println("  Parsed Movie Name: ${response.name}")
        println("  Parsed Poster URL: ${response.posterUrl}")
        println("  Parsed Plot: ${response.plot}")
        println("  Parsed Score: ${response.score}")
        println("  Parsed Actors: ${response.actors?.map { it.actor.name }}")
        println("  Parsed Backdrop Poster: ${response.backgroundPosterUrl}")

        assert(response.score != null) { "Movie score was not resolved from Cinemeta!" }
        assert(response.actors?.isNotEmpty() == true) { "Movie cast was not resolved from Cinemeta!" }
        assert(response.backgroundPosterUrl?.startsWith("http") == true) { "Movie backdrop poster was not resolved from Cinemeta!" }
        
        assert(response is MovieLoadResponse) { "Expected MovieLoadResponse for a movie page!" }
        val movieData = (response as MovieLoadResponse).dataUrl
        
        println("[*] Testing loadLinks for: $movieData")
        val resolvedLinks = mutableListOf<com.lagradost.cloudstream3.utils.ExtractorLink>()
        
        provider.loadLinks(movieData, isCasting = false, subtitleCallback = {}, callback = { link ->
            resolvedLinks.add(link)
        })
        
        assert(resolvedLinks.isNotEmpty()) { "Failed to resolve any stream links!" }
        
        resolvedLinks.forEach { link ->
            println("  - Label: ${link.name} | Quality: ${link.quality}p")
            println("    Resolved URL: ${link.url}")
            assert(link.url.startsWith("http")) { "Stream link is not a valid HTTP URL!" }
        }
        println("[+] Load & Links Test Passed.")
    }
}
