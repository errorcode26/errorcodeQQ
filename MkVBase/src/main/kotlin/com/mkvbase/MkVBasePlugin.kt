package com.mkvbase

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * MkVBase plugin entry point registering providers and extractors.
 */
@CloudstreamPlugin
class MkVBasePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MkVBaseProvider())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(HubCloud())
    }
}
