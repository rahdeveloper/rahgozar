package com.rahgozar.app.fmt

import com.rahgozar.app.AppConfig
import com.rahgozar.app.dto.V2rayNShareItem
import com.rahgozar.app.dto.entities.ProfileItem
import com.rahgozar.app.util.JsonUtil
import com.rahgozar.app.util.LogUtil
import com.rahgozar.app.util.Utils

object V2rayNFmt : FmtBase() {
    fun parse(str: String): ProfileItem? {
        try {
            val jsonBase64Payload = str.substringAfterLast('/')
            val jsonPayload = Utils.decode(jsonBase64Payload)
            val v2rayNShareItem = JsonUtil.fromJson(jsonPayload, V2rayNShareItem::class.java)
            return v2rayNShareItem?.toProfileItem()
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to parse V2rayN format", e)
        }
        return null
    }
}