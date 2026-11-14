package app.vellum.shield.data

data class DnsUpstreamSpec(
    val index: Int,
    val id: String,
    val name: String,
    val primaryDohUrl: String,
    val secondaryDohUrl: String,
    val bootstrapUdpIp: String
)

object FilterMetadata {
    // Exact 1-to-1 bit order matching App.tsx categoryData[0..7] and compile_blocklists.py:
    const val BIT_GAME_ADS  = 1 shl 0 // 0x01 Game & In-App Ads
    const val BIT_WEB_ADS   = 1 shl 1 // 0x02 Web Banners & Popups
    const val BIT_TRACKERS  = 1 shl 2 // 0x04 Cross-App Trackers
    const val BIT_MALWARE   = 1 shl 3 // 0x08 Malware & Scam Shield
    const val BIT_OEM       = 1 shl 4 // 0x10 Phone Brand Telemetry
    const val BIT_GAMBLING  = 1 shl 5 // 0x20 Betting & Casino Popups
    const val BIT_AFFILIATE = 1 shl 6 // 0x40 Shopping & Affiliate Links
    const val BIT_SOCIAL    = 1 shl 7 // 0x80 Social Media Trackers

    const val MASK_LIGHT    = BIT_GAME_ADS or BIT_MALWARE
    const val MASK_BALANCED = BIT_GAME_ADS or BIT_WEB_ADS or BIT_TRACKERS or BIT_MALWARE or BIT_OEM or BIT_GAMBLING
    const val MASK_STRICT   = 0xFF

    val CATEGORY_BITS = intArrayOf(
        BIT_GAME_ADS,
        BIT_WEB_ADS,
        BIT_TRACKERS,
        BIT_MALWARE,
        BIT_OEM,
        BIT_GAMBLING,
        BIT_AFFILIATE,
        BIT_SOCIAL
    )

    val ENGINE_KEYS = arrayOf(
        "cname_uncloak",
        "anti_storm_ttl",
        "block_doh_canary",
        "captive_portal_fallback",
        "start_on_boot"
    )

    val UPSTREAMS: List<DnsUpstreamSpec> = listOf(
        DnsUpstreamSpec(
            index = 0,
            id = "google",
            name = "Google DNS",
            primaryDohUrl = "https://8.8.8.8/dns-query",
            secondaryDohUrl = "https://8.8.4.4/dns-query",
            bootstrapUdpIp = "8.8.8.8"
        ),
        DnsUpstreamSpec(
            index = 1,
            id = "cloudflare",
            name = "Cloudflare",
            primaryDohUrl = "https://1.1.1.1/dns-query",
            secondaryDohUrl = "https://1.0.0.1/dns-query",
            bootstrapUdpIp = "1.1.1.1"
        ),
        DnsUpstreamSpec(
            index = 2,
            id = "quad9",
            name = "Quad9",
            primaryDohUrl = "https://9.9.9.9/dns-query",
            secondaryDohUrl = "https://149.112.112.112/dns-query",
            bootstrapUdpIp = "9.9.9.9"
        ),
        DnsUpstreamSpec(
            index = 3,
            id = "adguard_unfiltered",
            name = "AdGuard Unfiltered",
            primaryDohUrl = "https://94.140.14.140/dns-query",
            secondaryDohUrl = "https://94.140.14.141/dns-query",
            bootstrapUdpIp = "94.140.14.140"
        ),
        DnsUpstreamSpec(
            index = 4,
            id = "custom",
            name = "Custom DoH URL",
            primaryDohUrl = "",
            secondaryDohUrl = "https://8.8.8.8/dns-query",
            bootstrapUdpIp = "8.8.8.8"
        )
    )

    fun categoryLabelForMask(matchedMask: Int): String {
        return when {
            (matchedMask and BIT_GAME_ADS) != 0 -> "Game & In-App Ads"
            (matchedMask and BIT_WEB_ADS) != 0 -> "Web Ads"
            (matchedMask and BIT_TRACKERS) != 0 -> "Cross-App Tracker"
            (matchedMask and BIT_MALWARE) != 0 -> "Malware & Scam"
            (matchedMask and BIT_OEM) != 0 -> "Brand Telemetry"
            (matchedMask and BIT_GAMBLING) != 0 -> "Betting Popup"
            (matchedMask and BIT_AFFILIATE) != 0 -> "Shopping Link"
            (matchedMask and BIT_SOCIAL) != 0 -> "Social Tracker"
            else -> "Custom Rule"
        }
    }

    fun intensityTitleForMask(mask: Int): String {
        return when (mask and 0xFF) {
            MASK_LIGHT -> "Light"
            MASK_BALANCED -> "Balanced"
            MASK_STRICT -> "Strict"
            else -> "Custom"
        }
    }

    fun upstreamById(id: String): DnsUpstreamSpec {
        return UPSTREAMS.firstOrNull { it.id == id } ?: UPSTREAMS.first()
    }

    fun upstreamByIndex(idx: Int): DnsUpstreamSpec {
        return UPSTREAMS.getOrElse(idx) { UPSTREAMS.first() }
    }
}
