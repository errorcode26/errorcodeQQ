package com.bintv

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class BinTVPlugin : BasePlugin() {
    override fun load() {
        // Register provider
        registerMainAPI(BinTVProvider())
    }
}
