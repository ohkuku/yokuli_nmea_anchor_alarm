package com.yokuli.anchorwatch.data.sharing

import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkAddressProvider @Inject constructor() {
    fun localAddresses(): List<String> = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network -> java.util.Collections.list(network.inetAddresses).map { network.displayName to it } }
            .filter { (_, address) -> !address.isLoopbackAddress && !address.isLinkLocalAddress }
            .sortedWith(compareBy<Pair<String, java.net.InetAddress>> { (_, address) -> if (address is Inet4Address) 0 else 1 }.thenBy { it.second.hostAddress })
            .mapNotNull { (_, address) -> address.hostAddress?.substringBefore('%') }
            .distinct()
    }.getOrDefault(emptyList())
}
