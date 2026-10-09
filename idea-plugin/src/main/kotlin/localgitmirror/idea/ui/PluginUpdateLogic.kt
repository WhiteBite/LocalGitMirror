package localgitmirror.idea.ui

internal object PluginUpdateLogic {

  sealed interface Decision {
    object Unknown : Decision
    object UpToDate : Decision
    data class Update(val current: String, val remote: String) : Decision
  }

  fun decide(currentVersion: String?, remoteVersion: String?): Decision {
    val cur = currentVersion?.trim()?.removePrefix("v").orEmpty()
    val rem = remoteVersion?.trim()?.removePrefix("v").orEmpty()
    if (cur.isEmpty() || rem.isEmpty() || cur == "?") return Decision.Unknown
    if (cur == rem) return Decision.UpToDate
    return if (compareVersions(rem, cur) > 0) Decision.Update(cur, rem) else Decision.UpToDate
  }

  fun compareVersions(a: String, b: String): Int {
    val pa = a.split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
      val x = pa.getOrElse(i) { 0 }
      val y = pb.getOrElse(i) { 0 }
      if (x != y) return x - y
    }
    return 0
  }
}
