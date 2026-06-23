version = 1

cloudstream {
    description = "CSGuard Protection Plugin"
    authors = listOf("errorcodeQQ")
    status = 1
    tvTypes = listOf("Others")
    requiresResources = false
    language = "en"
    iconUrl = "https://raw.githubusercontent.com/google/material-design-icons/master/png/communication/security/png48/security_48dp.png"
}

android {
    namespace = "com.csguard"
    lint { abortOnError = false }
    buildFeatures {
        buildConfig = true
        viewBinding = false
    }
}
