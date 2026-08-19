package com.rahgozar.app.enums

enum class RoutingType(val fileName: String) {
    WHITE("custom_routing_white"),
    BLACK("custom_routing_black"),
    GLOBAL("custom_routing_global"),
    WHITE_IRAN("custom_routing_white_iran"),
    WHITE_RUSSIA("custom_routing_white_russia"),

    /**
     * Iran, Russia and China all routed direct.
     *
     * The three the app's users are actually in. Sending local traffic through
     * a foreign tunnel is slower, more conspicuous, and breaks any local
     * service that geolocates — so it goes straight out, and only the rest is
     * tunnelled.
     */
    LOCAL_DIRECT("custom_routing_local_direct");

    companion object {
        fun fromIndex(index: Int): RoutingType {
            return when (index) {
                0 -> WHITE
                1 -> BLACK
                2 -> GLOBAL
                3 -> WHITE_IRAN
                4 -> WHITE_RUSSIA
                5 -> LOCAL_DIRECT
                else -> WHITE
            }
        }
    }
}
