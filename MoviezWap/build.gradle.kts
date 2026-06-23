version = 1

cloudstream {
    description = "MoviezWap Provider"
    authors = listOf("errorcodeQQ")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
    requiresResources = false
    language = "te"
    isCrossPlatform = true
}

android {
    namespace = "com.moviezwap"
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}

