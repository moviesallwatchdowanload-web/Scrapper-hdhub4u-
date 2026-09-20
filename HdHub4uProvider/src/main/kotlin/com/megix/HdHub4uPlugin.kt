package com.megix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HdHub4uPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HdHub4uProvider())
    }
}
