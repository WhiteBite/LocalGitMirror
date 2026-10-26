package localgitmirror.idea.net

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection

internal class CancellableConnection(private val delegate: HttpURLConnection) : HttpURLConnection(delegate.url) {
  private fun <T> io(action: () -> T): T = Cancellation.blocking(delegate::disconnect, action)

  override fun connect() = io { delegate.connect() }
  override fun disconnect() = delegate.disconnect()
  override fun usingProxy(): Boolean = delegate.usingProxy()
  override fun getResponseCode(): Int = io { delegate.responseCode }
  override fun getResponseMessage(): String? = io { delegate.responseMessage }
  override fun getHeaderField(name: String?): String? = io { delegate.getHeaderField(name) }
  override fun getHeaderFields(): Map<String, List<String>> = io { delegate.headerFields }
  override fun getHeaderField(n: Int): String? = io { delegate.getHeaderField(n) }
  override fun getHeaderFieldKey(n: Int): String? = io { delegate.getHeaderFieldKey(n) }
  override fun getContentLengthLong(): Long = io { delegate.contentLengthLong }
  override fun getInputStream(): InputStream = input(io { delegate.inputStream })
  override fun getErrorStream(): InputStream? = io { delegate.errorStream }?.let(::input)
  override fun getOutputStream(): OutputStream = object : FilterOutputStream(io { delegate.outputStream }) {
    override fun write(b: Int) = io { out.write(b) }
    override fun write(b: ByteArray, off: Int, len: Int) = io { out.write(b, off, len) }
    override fun flush() = io { out.flush() }
    override fun close() = io { out.close() }
  }

  private fun input(stream: InputStream): InputStream = object : FilterInputStream(stream) {
    override fun read(): Int = io { `in`.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int = io { `in`.read(b, off, len) }
    override fun close() = io { `in`.close() }
  }

  override fun setRequestMethod(method: String) { delegate.requestMethod = method }
  override fun getRequestMethod(): String = delegate.requestMethod
  override fun setRequestProperty(key: String, value: String) = delegate.setRequestProperty(key, value)
  override fun addRequestProperty(key: String, value: String) = delegate.addRequestProperty(key, value)
  override fun getRequestProperty(key: String): String? = delegate.getRequestProperty(key)
  override fun setConnectTimeout(timeout: Int) { delegate.connectTimeout = timeout }
  override fun getConnectTimeout(): Int = delegate.connectTimeout
  override fun setReadTimeout(timeout: Int) { delegate.readTimeout = timeout }
  override fun getReadTimeout(): Int = delegate.readTimeout
  override fun setDoOutput(value: Boolean) { delegate.doOutput = value }
  override fun getDoOutput(): Boolean = delegate.doOutput
  override fun setDoInput(value: Boolean) { delegate.doInput = value }
  override fun getDoInput(): Boolean = delegate.doInput
  override fun setUseCaches(value: Boolean) { delegate.useCaches = value }
  override fun getUseCaches(): Boolean = delegate.useCaches
  override fun setInstanceFollowRedirects(value: Boolean) { delegate.instanceFollowRedirects = value }
  override fun getInstanceFollowRedirects(): Boolean = delegate.instanceFollowRedirects
  override fun setFixedLengthStreamingMode(length: Int) = delegate.setFixedLengthStreamingMode(length)
  override fun setFixedLengthStreamingMode(length: Long) = delegate.setFixedLengthStreamingMode(length)
  override fun setChunkedStreamingMode(length: Int) = delegate.setChunkedStreamingMode(length)
}
