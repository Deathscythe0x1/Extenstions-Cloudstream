package com.katworld

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class KatWorldPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PikaHDProvider())
        registerMainAPI(KatMovieHDProvider())
        registerMainAPI(MoviesBabaProvider())
        registerMainAPI(KatDramaProvider())
        registerMainAPI(KatMovie4KProvider())
    }
}
