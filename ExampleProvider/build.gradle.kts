// Use an integer for version numbers
version = 1

cloudstream {
    description = "Example Provider Template"
    authors = listOf("author")
    language = "en"

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    // List of video source types. See:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf("Movie", "TvSeries", "Anime", "AnimeMovie")

    // iconUrl = "https://raw.githubusercontent.com/<user>/<repo>/<branch>/ExampleProvider/icon.png"

    requiresResources = false
    isCrossPlatform = true
}

android {
    namespace = "com.example"
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}

