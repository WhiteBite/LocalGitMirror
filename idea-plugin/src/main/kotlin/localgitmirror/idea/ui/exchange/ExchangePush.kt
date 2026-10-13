package localgitmirror.idea.ui.exchange

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import localgitmirror.idea.deps.MachineRole
import localgitmirror.idea.deps.RoleDetector
import localgitmirror.idea.net.HttpClient
import localgitmirror.idea.settings.MirrorSettingsService
import localgitmirror.idea.settings.SecretsStore
import localgitmirror.idea.ui.LocalGitMirrorPanel
import java.net.URI
import java.net.URL
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

private const val RECONNECT_SECONDS = 10L
private const val PING_SECONDS = 30L

// WORK machines never open this socket: a persistent connection to the home server is exactly the EDR signal stealth mode exists to avoid.
internal class ExchangePushClient(private val onExchangeChanged: () -> Unit) {
  private val json = Json { ignoreUnknownKeys = true }
  private var socket: WebSocket? = null
  private var baseUrl: URL? = null
  private var wsUri: URI? = null
  private var insecureTls = false

  @Volatile
  private var stopped = false

  fun start(base: URL, apiKey: String, insecureTls: Boolean) {
    this.baseUrl = base
    this.insecureTls = insecureTls
    val scheme = if (base.protocol == "https") "wss" else "ws"
    val port = if (base.port != -1) base.port else base.defaultPort
    val key = java.net.URLEncoder.encode(apiKey, "UTF-8")
    wsUri = URI("$scheme://${base.host}:$port/ws/exchange?key=$key")
    connect()
  }

  fun stop() {
    stopped = true
    runCatching { socket?.abort() }
    socket = null
  }

  // A fresh JDK client per attempt: java.net.http (Java 17) offers no shutdown,
  // and a rebuild also picks up a TLS pin stored since the last connect.
  private fun connect() {
    val uri = wsUri ?: return
    val base = baseUrl ?: return
    if (stopped) return
    val builder = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
    HttpClient.sslContextFor(base, insecureTls)?.let { ctx ->
      builder.sslContext(ctx)
    }
    builder.build()
      .newWebSocketBuilder()
      .connectTimeout(Duration.ofSeconds(10))
      .buildAsync(uri, listener)
      .whenComplete { ws, err ->
        if (stopped || err != null || ws == null) {
          scheduleReconnect()
          return@whenComplete
        }
        socket = ws
        schedulePing()
      }
  }

  private fun scheduleReconnect() {
    if (stopped) return
    AppExecutorUtil.getAppScheduledExecutorService()
      .schedule({ connect() }, RECONNECT_SECONDS, TimeUnit.SECONDS)
  }

  private fun schedulePing() {
    if (stopped) return
    AppExecutorUtil.getAppScheduledExecutorService()
      .schedule({
        val ws = socket
        if (ws != null && !stopped) {
          ws.sendText("ping", true)
          schedulePing()
        }
      }, PING_SECONDS, TimeUnit.SECONDS)
  }

  private val listener = object : WebSocket.Listener {
    override fun onText(
      webSocket: WebSocket,
      data: CharSequence,
      last: Boolean,
    ): CompletableFuture<*>? {
      val type = runCatching {
        json.parseToJsonElement(data.toString()).jsonObject["type"]?.jsonPrimitive?.contentOrNull
      }.getOrNull()
      if (type == "exchange_change") {
        ApplicationManager.getApplication().invokeLater { onExchangeChanged() }
      }
      webSocket.request(1)
      return null
    }

    override fun onClose(
      webSocket: WebSocket,
      statusCode: Int,
      reason: String?,
    ): CompletableFuture<*>? {
      socket = null
      scheduleReconnect()
      return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
      socket = null
      scheduleReconnect()
    }
  }
}

internal fun LocalGitMirrorPanel.ensureExchangePush() {
  if (exchangePush != null) return
  val s = try {
    service<MirrorSettingsService>().state
  } catch (_: Throwable) {
    return
  }
  if (s.baseUrl.isBlank()) return
  val isWork = try {
    RoleDetector.detect(s) == MachineRole.WORK
  } catch (_: Throwable) {
    false
  }
  if (isWork) return
  val url = try {
    URL(s.baseUrl.trim())
  } catch (_: Throwable) {
    return
  }
  exchangePush = ExchangePushClient { refreshExchangeInBackground() }.also {
    it.start(url, SecretsStore.mirrorApiKey, s.mirrorInsecureTls)
  }
}

internal fun LocalGitMirrorPanel.stopExchangePush() {
  exchangePush?.stop()
  exchangePush = null
}
