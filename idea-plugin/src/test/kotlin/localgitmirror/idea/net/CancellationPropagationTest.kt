package localgitmirror.idea.net

import com.intellij.openapi.progress.ProcessCanceledException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancellationPropagationTest {
  @Test
  fun `cancellation is not converted to a network error`() {
    assertFailsWith<ProcessCanceledException> {
      HttpClient.classifyError(ProcessCanceledException())
    }
  }

  @Test
  fun `cancelling a running git command terminates the process`() {
    val process = ProcessBuilder("git", "-c", "alias.wait=!echo ready; sleep 60", "wait").start()
    try {
      assertTrue(process.inputStream.bufferedReader().readLine() == "ready")

      assertFailsWith<ProcessCanceledException> {
        Cancellation.waitFor(process, 30) { throw ProcessCanceledException() }
      }

      assertFalse(process.isAlive)
    } finally {
      process.destroyForcibly()
    }
  }

  @Test
  fun `cancelling blocked socket read closes the socket and unwinds`() {
    ServerSocket(0).use { server ->
      Socket("127.0.0.1", server.localPort).use { client ->
        server.accept().use {
          val started = CountDownLatch(1)
          val stopped = CountDownLatch(1)

          assertFailsWith<ProcessCanceledException> {
            Cancellation.blocking(
              abort = { client.close() },
              action = {
                started.countDown()
                try { client.getInputStream().read() } finally { stopped.countDown() }
              },
              check = { if (started.count == 0L) throw ProcessCanceledException() },
            )
          }

          assertTrue(stopped.await(5, TimeUnit.SECONDS))
          assertTrue(client.isClosed)
        }
      }
    }
  }
}
