package com.yokuli.anchorwatch.data.nmea

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.Socket
import javax.inject.Inject

class NmeaEndpointPreflight @Inject constructor() {
    suspend fun check(profile: ConnectionProfile): Result<Unit> = withContext(Dispatchers.IO) {
        val result=runCatching {
            validate(profile)?.let { error(it) }
            if (profile.protocol == Protocol.TCP) checkTcp(profile) else checkUdp(profile)
        }
        result.fold(onSuccess={Result.success(Unit)},onFailure={error->Result.failure(IllegalStateException(when(error){is SocketTimeoutException->"The endpoint responded, but no valid NMEA sentence arrived within 4 seconds.";else->error.message?.takeIf{it.isNotBlank()}?:"The NMEA endpoint test failed."},error))})
    }

    fun validate(profile: ConnectionProfile): String? {
        if (profile.port !in 1..65535) return "Port must be between 1 and 65535."
        if (profile.protocol == Protocol.TCP) {
            val host = profile.host
            if (host.isBlank()) return "Host or IP address is required."
            if (host != host.trim() || host.any(Char::isWhitespace) || "://" in host || '/' in host) return "Enter a host name or IP address, not a URL."
            if (host.length > 253) return "Host name is too long."
        }
        return null
    }

    private fun checkTcp(profile: ConnectionProfile) {
        val address = runCatching { InetAddress.getByName(profile.host) }.getOrElse { error("Host name or IP address could not be resolved.") }
        Socket().use { socket ->
            socket.connect(InetSocketAddress(address, profile.port), 4_000)
            socket.soTimeout = 4_000
            val buffer = ByteArray(4_096)
            val splitter = NmeaStreamSplitter()
            val parser = Nmea0183Parser()
            val deadline = System.nanoTime() + 4_000_000_000L
            while (System.nanoTime() < deadline) {
                val count = socket.getInputStream().read(buffer)
                if (count < 0) error("The server closed the test connection before sending NMEA data.")
                val valid = splitter.feed(buffer, count).any { parser.parse(it, profile.requireChecksum) != null }
                if (valid) return
            }
            error("Connected, but no valid NMEA data was received within 4 seconds.")
        }
    }

    private fun checkUdp(profile: ConnectionProfile) {
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(profile.port))
            socket.soTimeout = 4_000
            val bytes = ByteArray(8_192)
            val packet = DatagramPacket(bytes, bytes.size)
            socket.receive(packet)
            val parser = Nmea0183Parser()
            val valid = NmeaStreamSplitter().feed(packet.data, packet.length).any { parser.parse(it, profile.requireChecksum) != null }
            if (!valid) error("UDP traffic arrived, but it did not contain a valid NMEA sentence.")
        }
    }
}
