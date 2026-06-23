package com.example

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ExampleProviderPlugin : BasePlugin() {
    override fun load() {
        // Register all MainAPI providers here
        registerMainAPI(ExampleProvider())

        // Register any custom extractors here, e.g.:
        // registerExtractorAPI(ExampleExtractor())
    }
}
