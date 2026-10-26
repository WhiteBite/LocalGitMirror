package localgitmirror.idea.net

import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal object Cancellation {
  fun check() {
    ProgressManager.checkCanceled()
    if (Thread.currentThread().isInterrupted) throw ProcessCanceledException()
  }

  fun rethrow(t: Throwable) {
    if (t is ProcessCanceledException) throw t
    if (t is java.util.concurrent.CancellationException) throw t
    check()
  }

  fun waitFor(process: Process, seconds: Long, check: () -> Unit = ::check): Boolean {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
    try {
      while (true) {
        check()
        if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
          check()
          return true
        }
        if (System.nanoTime() >= deadline) return false
      }
    } finally {
      if (process.isAlive) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        process.waitFor(2, TimeUnit.SECONDS)
      }
    }
  }

  fun <T> blocking(abort: () -> Unit, action: () -> T, check: () -> Unit = ::check): T {
    var future: Future<T>? = null
    var finished = false
    try {
      check()
      val pending = AppExecutorUtil.getAppExecutorService().submit(Callable { action() })
      future = pending
      while (true) {
        check()
        try {
          val value = pending.get(100, TimeUnit.MILLISECONDS)
          check()
          finished = true
          return value
        } catch (_: TimeoutException) {
          continue
        } catch (e: ExecutionException) {
          finished = true
          throw e.cause ?: e
        }
      }
    } finally {
      if (!finished) {
        future?.cancel(true)
        AppExecutorUtil.getAppExecutorService().execute(abort)
      }
    }
  }
}
