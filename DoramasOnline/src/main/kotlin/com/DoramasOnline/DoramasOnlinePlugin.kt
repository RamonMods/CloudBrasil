package com.DoramasOnline

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DoramasOnlinePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DoramasOnline())
    }
}
