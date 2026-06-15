package localgitmirror.idea.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.DatagramPacket
import java.net.DatagramSocket

object LanDiscovery {
  private const val BROADCAST_PORT = 37020

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
   * Listen for LAN beacon broadcasts for up to [timeoutMs] milliseconds.
   * Returns all unique discovered servers (usually 0 or 1).
   */
  fun discover(timeoutMs: Int = 5000): List<DiscoveredServer> {
    val results = mutableListOf<DiscoveredServer>()
    val seen = mutableSetOf<String>()

    var socket: DatagramSocket? = null
    try {
      socket = DatagramSocket(BROADCAST_PORT)
      socket.soTimeout = timeoutMs
      socket.reuseAddress = true
      socket.broadcast = true

      val buf = ByteArray(256)
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

        val raw = String(packet.data, 0, packet.length, Charsets.UTF_8)
        val server = parsePayload(raw) ?: continue
        val key = "${server.ip}:${server.port}"
        if (seen.add(key)) {
          results.add(server)
        }
      }
    } catch (_: Exception) {
      // Silently fail — network may be unavailable
    } finally {
      try { socket?.close() } catch (_: Exception) {}
    }

    return results
  }

  private fun parsePayload(raw: String): DiscoveredServer? {
    return try {
      val json = Json.parseToJsonElement(raw).jsonObject
      val id = json["id"]?.jsonPrimitive?.content ?: return null
      if (id != "lgm") return null
      val ip = json["ip"]?.jsonPrimitive?.content ?: return null
      val port = json["port"]?.jsonPrimitive?.intOrNull ?: return null
      val tls = json["tls"]?.jsonPrimitive?.booleanOrNull ?: false
      if (ip.isBlank() || port <= 0) return null
      DiscoveredServer(ip, port, tls)
    } catch (_: Exception) {
      null
    }
  }
}
