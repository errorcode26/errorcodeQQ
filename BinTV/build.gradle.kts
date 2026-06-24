version = 1

cloudstream {
    description = "Live sports streams from BinTV."
    authors = listOf("errorcodeQQ")
    status = 1
    tvTypes = listOf("Live")
    language = "en"
    iconUrl = "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEhsQsag_KQPokaomZsgDV4fiXQ_fi494N6wV8cwKyQQuhSnOh1pAl4lV2Ur-yHCG6IFBimoeWaZKiOTQyyEmfYLetghJRbhyoTHZuzfbZ9VOWZV5aNE4L4akyYmk5D1sB-QLzVQLy200JxziBg0Wwetdxb0Ybf7oqv4R1W8t49rsYLsFkLHZuNPL42I8Q/s512/letter-b.png"
    requiresResources = false
}

android {
    namespace = "com.bintv"
    compileSdk = 35
    defaultConfig {
        minSdk = 21
    }
    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}
