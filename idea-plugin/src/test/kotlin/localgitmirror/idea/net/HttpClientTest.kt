package localgitmirror.idea.net

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientTest {

  @Test
  fun `classify connect exception`() {
    val info = HttpClient.classifyError(ConnectException("Connection refused"))
    assertEquals("connect", info.type)
  }

  @Test
  fun `classify dns exception`() {
    val info = HttpClient.classifyError(UnknownHostException("unknown host"))
    assertEquals("dns", info.type)
  }

  @Test
  fun `classify timeout exception`() {
    val info = HttpClient.classifyError(SocketTimeoutException("timed out"))
    assertEquals("timeout", info.type)
  }

  @Test
  fun `classify ssl exception`() {
    val info = HttpClient.classifyError(SSLException("certificate"))
    assertEquals("ssl", info.type)
  }

  @Test
  fun `classify generic exception`() {
    val info = HttpClient.classifyError(IllegalStateException("boom"))
    assertEquals("network", info.type)
  }
}
