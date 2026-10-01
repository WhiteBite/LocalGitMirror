package localgitmirror.idea.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.jmdns.JmDNS

object LanDiscovery {
  private const val MDNS_SERVICE_TYPE = "_http._tcp.local."
  private const val MDNS_SERVICE_NAME = "DocCache"
  private const val UDP_FALLBACK_PORT = 37020

  data class DiscoveredServer(
    val ip: String,
    val port: Int,
    val tls: Boolean
  ) {
    fun toUrl(): String {
      val scheme = if (tls) "https" else "http"
      return "$scheme://$ip:$port"
    }
  }

  /**
   * Discover servers on LAN via mDNS first, then UDP fallback.
   * [authPassword] (SYNC_PASSWORD) enables HMAC verification of UDP beacons:
   * a configured client then ignores embedded IPs from untagged or
   * incorrectly tagged beacons, closing the LAN spoofing hole.
   */
  fun discover(timeoutMs: Int = 6000, authPassword: String? = null): List<DiscoveredServer> {
    val results = mutableListOf<DiscoveredServer>()
    val seen = mutableSetOf<String>()

    // 1. Try mDNS (standard, EDR-invisible)
    try {
      val mdnsResults = discoverMdns(timeoutMs)
      for (s in mdnsResults) {
        val key = "${s.ip}:${s.port}"
        if (seen.add(key)) results.add(s)
      }
      if (results.isNotEmpty()) return results
    } catch (_: Exception) {
      // mDNS may not be available — continue to UDP fallback
    }

    // 2. UDP fallback (binary payload)
    try {
      val udpResults = discoverUdp(timeoutMs, authPassword)
      for (s in udpResults) {
        val key = "${s.ip}:${s.port}"
        if (seen.add(key)) results.add(s)
      }
      if (results.isNotEmpty()) return results
    } catch (_: Exception) {
      // Silently fail
    }

    return results
  }

  // ── mDNS discovery ──────────────────────────────────────────────────

  @Suppress("HttpCallOnEdt") // always called from Thread in MirrorSettingsConfigurable
  private fun discoverMdns(timeoutMs: Int): List<DiscoveredServer> {
    val results = mutableListOf<DiscoveredServer>()
    var jmdns: JmDNS? = null
    try {
      jmdns = JmDNS.create(InetAddress.getLocalHost())
      val services = jmdns.list(MDNS_SERVICE_TYPE, timeoutMs.toLong())
      for (info in services) {
        if (!info.name.startsWith(MDNS_SERVICE_NAME)) continue
        val port = info.port
        if (port <= 0) continue
        val tls = info.getPropertyString("tls")?.equals("true", ignoreCase = true) ?: false
        for (addr in info.inetAddresses) {
          val ip = addr.hostAddress ?: continue
          results.add(DiscoveredServer(ip, port, tls))
        }
      }
    } finally {
      try { jmdns?.close() } catch (_: Exception) {}
    }
    return results
  }

  // ── UDP fallback (binary: port(2) + flags(1)) ──────────────────────

  @Suppress("HttpCallOnEdt") // always called from Thread in MirrorSettingsConfigurable
  private fun discoverUdp(timeoutMs: Int, authPassword: String?): List<DiscoveredServer> {
    val results = mutableListOf<DiscoveredServer>()
    val seen = mutableSetOf<String>()

    var socket: DatagramSocket? = null
    try {
      socket = DatagramSocket(UDP_FALLBACK_PORT)
      socket.soTimeout = timeoutMs
      socket.reuseAddress = true
      socket.broadcast = true

      val buf = ByteArray(64)
      val deadline = System.currentTimeMillis() + timeoutMs

      while (System.currentTimeMillis() < deadline) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) break
        socket.soTimeout = remaining.toInt().coerceAtLeast(500)

        val packet = DatagramPacket(buf, buf.size)
        try {
          socket.receive(packet)
        } catch (_: java.net.SocketTimeoutException) {
          break
        }

        if (packet.length < 3) continue
        val data = packet.data
        val port = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val flags = data[2].toInt() and 0xFF
        val tls = (flags and 0x01) != 0
        val hasTag = (flags and 0x02) != 0
        // v2 beacon: port(2) + flags(1) + ip4(4) [+ hmac(4)] — the server's
        // chosen LAN IP. On multi-homed hosts it differs from the UDP source
        // address (the OS default-route interface), so prefer the embedded
        // one and keep the source as a secondary candidate. Legacy 3-byte
        // beacons fall back to the source address only.
        val embedded = if (packet.length >= 7) {
          "${data[3].toInt() and 0xFF}.${data[4].toInt() and 0xFF}.${data[5].toInt() and 0xFF}.${data[6].toInt() and 0xFF}"
        } else {
          null
        }
        // Anti-spoofing: a configured client (knows SYNC_PASSWORD) trusts the
        // embedded IP only behind a valid HMAC tag; untagged/invalid beacons
        // degrade to the packet source. Unconfigured (first-run) clients keep
        // the legacy behavior — the user confirms the server manually anyway.
        val clientConfigured = !authPassword.isNullOrBlank()
        val tagValid = if (hasTag && packet.length >= 11 && clientConfigured) {
          val mac = javax.crypto.Mac.getInstance("HmacSHA256")
          mac.init(javax.crypto.spec.SecretKeySpec(authPassword.orEmpty().toByteArray(Charsets.UTF_8), "HmacSHA256"))
          mac.update(data, 0, 7)
          mac.doFinal().copyOfRange(0, 4).contentEquals(data.copyOfRange(7, 11))
        } else {
          false
        }
        val trustedEmbedded: String? = when {
          embedded == null -> null
          !clientConfigured -> embedded
          hasTag && tagValid -> embedded
          else -> null
        }
        val source = packet.address?.hostAddress
        val candidates = buildList {
          if (trustedEmbedded != null) add(trustedEmbedded)
          if (source != null && source != trustedEmbedded) add(source)
        }
        if (candidates.isEmpty()) continue
        for (ip in candidates) {
          val server = DiscoveredServer(ip, port, tls)
          val key = "${server.ip}:${server.port}"
          if (seen.add(key)) {
            results.add(server)
          }
        }
      }
    } finally {
      try { socket?.close() } catch (_: Exception) {}
    }

    return results
  }
}
