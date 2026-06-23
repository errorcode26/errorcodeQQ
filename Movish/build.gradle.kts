version = 1

cloudstream {
    description = "Movish Provider"
    authors = listOf("errorcodeQQ")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Live")
    requiresResources = false
    language = "en"
    isCrossPlatform = true
}

android {
    namespace = "com.movish"
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}

