package com.rahgozar.app.enums

import com.rahgozar.app.AppConfig

enum class EConfigType(val value: Int, val protocolScheme: String) {
    VMESS(1, AppConfig.VMESS),
    CUSTOM(2, AppConfig.CUSTOM),
    SHADOWSOCKS(3, AppConfig.SHADOWSOCKS),
    SOCKS(4, AppConfig.SOCKS),
    VLESS(5, AppConfig.VLESS),
    TROJAN(6, AppConfig.TROJAN),
    WIREGUARD(7, AppConfig.WIREGUARD),

    //    TUIC(8, AppConfig.TUIC),
    HYSTERIA2(9, AppConfig.HYSTERIA2),
    HYSTERIA(900, AppConfig.HYSTERIA),
    HTTP(10, AppConfig.HTTP),

    // Not an Xray protocol. A profile of this type is handed to the openvpn3
    // core instead, by OpenVpnService — see docs/OPENVPN3-INTEGRATION.md.
    OPENVPN(11, AppConfig.OPENVPN),

    // Not an Xray protocol either: the whole sing-box configuration rides in
    // ProfileItem.singboxConfig and SingBoxService runs it through libbox.
    SINGBOX(12, AppConfig.SINGBOX),
    POLICYGROUP(101, AppConfig.CUSTOM),
    PROXYCHAIN(102, AppConfig.CUSTOM);

    companion object {
        fun fromInt(value: Int) = entries.firstOrNull { it.value == value }
    }
}