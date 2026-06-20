package localgitmirror.idea.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@State(name = "LocalGitMirrorOperationsHistory", storages = [Storage("localgitmirror-history.xml")])
class OperationsHistoryService : PersistentStateComponent<OperationsHistoryService.State> {
  data class Entry(
    var timestamp: String = "",
    var operation: String = "",
    var status: String = "",
    var details: String = ""
  )

  data class State(
    var entries: MutableList<Entry> = mutableListOf()
  )

  companion object {
    private const val MAX_ENTRIES = 40
  }

  private var state = State()
  // Subscribers notified on any change (add/clear). Panel uses this to refresh.
  private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

  fun addChangeListener(l: () -> Unit) { listeners.add(l) }
  fun removeChangeListener(l: () -> Unit) { listeners.remove(l) }
  private fun fireChanged() {
    for (l in listeners) {
      try { l() } catch (_: Throwable) { /* never let a bad listener break history */ }
    }
  }

  override fun getState(): State = state

  override fun loadState(state: State) {
    XmlSerializerUtil.copyBean(state, this.state)
  }

  fun add(operation: String, success: Boolean, details: String) {
    val ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    state.entries.add(
      Entry(
        timestamp = ts,
        operation = operation,
        status = if (success) "OK" else "FAIL",
        details = details.take(2000)
      )
    )
    while (state.entries.size > MAX_ENTRIES) {
      state.entries.removeAt(0)
    }
    fireChanged()
  }

  fun latest(limit: Int = 20): List<Entry> {
    val all = state.entries
    if (all.isEmpty()) return emptyList()
    return all.takeLast(limit).asReversed()
  }

  fun clear() {
    state.entries.clear()
    fireChanged()
  }
}
