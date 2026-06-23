version = 1

cloudstream {
    language = "en"
    description = "Stream anime from AniZen — trending series, latest episodes, sub & dub."
    authors = listOf("errorcodeQQ")
    status = 0 // 0 = Down (Disabled from compilation)

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta
     */
    tvTypes = listOf("Anime", "AnimeMovie")

    iconUrl = "https://anizen.tr/favicon.png"
    isCrossPlatform = true
}

