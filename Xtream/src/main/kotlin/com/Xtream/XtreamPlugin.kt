package com.Xtream

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class XtreamPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(Xtream())
    }
}
