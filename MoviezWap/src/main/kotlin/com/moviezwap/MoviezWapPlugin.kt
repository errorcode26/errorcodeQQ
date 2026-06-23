package com.moviezwap

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class MoviezWapPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(MoviezWapProvider())
    }
}
