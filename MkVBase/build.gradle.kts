// use an integer for version numbers
version = 1

cloudstream {
    description = "MkVBase — search aggregator for the 'Private Link Vault'. Returns file-host links (GDFlix, HubCloud) and resolves them via bundled extractors. TMDB metadata auto-loaded."
    language = "en"
    authors = listOf("errorcodeQQ")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "Others"
    )

    iconUrl = "https://mkvbase.site/logo.png"
    isCrossPlatform = true
}

android {
    namespace = "com.mkvbase"
}


