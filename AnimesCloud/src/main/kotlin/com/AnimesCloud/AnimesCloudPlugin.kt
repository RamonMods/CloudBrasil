package com.AnimesCloud

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimesCloudPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimesCloud())
    }
}
