package localgitmirror.idea.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceListener

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
   * Discover servers on LAN via mDNS first, then UDP fallback, then subnet scan.
   */
  fun discover(timeoutMs: Int = 6000): List<DiscoveredServer> {
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
      val udpResults = discoverUdp(timeoutMs)
      for (s in udpResults) {
        val key = "${s.ip}:${s.port}"
        if (seen.add(key)) results.add(s)
      }
      if (results.isNotEmpty()) return results
    } catch (_: Exception) {
      // Silently fail
    }

    // 3. Subnet scan fallback — probe common ports on local /24 subnet
    try {
      val scanResults = discoverSubnetScan(timeoutMs)
      for (s in scanResults) {
        val key = "${s.ip}:${s.port}"
        if (seen.add(key)) results.add(s)
      }
    } catch (_: Exception) {}

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
  private fun discoverUdp(timeoutMs: Int): List<DiscoveredServer> {
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
        val tls = (data[2].toInt() and 0x01) != 0
        val ip = packet.address.hostAddress ?: continue
        val server = DiscoveredServer(ip, port, tls)
        val key = "${server.ip}:${server.port}"
        if (seen.add(key)) {
          results.add(server)
        }
      }
    } finally {
      try { socket?.close() } catch (_: Exception) {}
    }

    return results
  }

  // ── Subnet scan fallback ────────────────────────────────────────────
  // Probes the local /24 subnet on common ports (443, 8443) for the
  // DocCache capabilities endpoint. Works through firewalls that block
  // mDNS/UDP broadcast. Parallelized with short connect timeouts.

  private val SCAN_PORTS = intArrayOf(443, 8443, 8080)

  private fun discoverSubnetScan(timeoutMs: Int): List<DiscoveredServer> {
    val results = java.util.concurrent.CopyOnWriteArrayList<DiscoveredServer>()
    val localIp = try {
      java.net.NetworkInterface.getNetworkInterfaces()?.toList()
        ?.flatMap { it.inetAddresses.toList() }
        ?.filterIsInstance<java.net.Inet4Address>()
        ?.firstOrNull { !it.isLoopbackAddress && it.hostAddress.startsWith("192.168.") }
        ?.hostAddress
    } catch (_: Exception) { null } ?: return emptyList()

    // Determine /24 prefix
    val prefix = localIp.substringBeforeLast('.') + "."
    val perHostTimeout = (timeoutMs / 50).coerceIn(200, 1500)  // ~120ms per host

    val executor = java.util.concurrent.Executors.newFixedThreadPool(32)
    val futures = mutableListOf<java.util.concurrent.Future<*>>()

    for (i in 1..254) {
      val ip = "$prefix$i"
      if (ip == localIp) continue  // skip self

      futures.add(executor.submit {
        for (port in SCAN_PORTS) {
          try {
            val url = java.net.URL("https://$ip:$port/api/capabilities")
            val conn = url.openConnection() as javax.net.ssl.HttpsURLConnection
            conn.connectTimeout = perHostTimeout
            conn.readTimeout = perHostTimeout
            conn.requestMethod = "GET"
            // Accept any cert (self-signed)
            conn.sslSocketFactory = trustAllFactory
            conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }

            val code = conn.responseCode
            if (code in 200..299) {
              val body = conn.inputStream.bufferedReader().readText()
              if (body.contains("DocCache")) {
                results.add(DiscoveredServer(ip, port, tls = true))
              }
            }
            conn.disconnect()
          } catch (_: Exception) {
            // Not reachable or not our server — skip
          }
        }
      })
    }

    // Wait with overall timeout
    try {
      executor.shutdown()
      executor.awaitTermination(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {}
    executor.shutdownNow()

    return results.toList()
  }

  private val trustAllFactory: javax.net.ssl.SSLSocketFactory by lazy {
    val trustAll = arrayOf<javax.net.ssl.TrustManager>(
      object : javax.net.ssl.X509TrustManager {
        override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
        override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, t: String) {}
        override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, t: String) {}
      }
    )
    val ctx = javax.net.ssl.SSLContext.getInstance("TLS")
    ctx.init(null, trustAll, java.security.SecureRandom())
    ctx.socketFactory
  }
}
