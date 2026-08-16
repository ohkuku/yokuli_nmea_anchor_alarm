package com.yokuli.anchorwatch.data.sharing

import com.yokuli.anchorwatch.data.nmea.ConnectionProfile
import com.yokuli.anchorwatch.data.nmea.Protocol

object NmeaSelfLoopPolicy {
    const val MESSAGE = "This endpoint is the App's own NMEA Sharing server. Choose the boat source instead to prevent a feedback loop."

    fun isLiteralLoop(profile: ConnectionProfile, sharingEnabled: Boolean, sharingPort: Int, localAddresses: Collection<String>): Boolean {
        if (!sharingEnabled || profile.protocol != Protocol.TCP || profile.port != sharingPort) return false
        val host = profile.host.trim().removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()
        return host in setOf("localhost", "127.0.0.1", "0.0.0.0", "::1") || localAddresses.any { it.substringBefore('%').equals(host, true) }
    }
}
