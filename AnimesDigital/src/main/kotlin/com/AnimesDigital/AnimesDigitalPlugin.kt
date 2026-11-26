package com.AnimesDigital

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimesDigitalPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesDigital())
    }
}
