package localgitmirror.idea.gitlab

import localgitmirror.idea.git.GitLocal
import java.io.File
import java.io.IOException

internal data class MrReviewSnapshot(
  val sourceSha: String,
  val targetSha: String,
  val baseSha: String,
  val files: List<String>,
)

internal data class MrReviewFileContent(val before: String?, val after: String?) {
  val binary: Boolean get() = before?.contains('\u0000') == true || after?.contains('\u0000') == true
}

internal class MrReviewGit(private val directory: File) {
  fun capture(source: String, target: String): MrReviewSnapshot {
    if (source.isBlank() || target.isBlank()) throw IOException("Select both source and target revisions")
    val sourceSha = command("rev-parse", "--verify", "--end-of-options", "$source^{commit}").trim()
    val targetSha = command("rev-parse", "--verify", "--end-of-options", "$target^{commit}").trim()
    val base = command("merge-base", targetSha, sourceSha).trim()
    val files = command("diff", "--name-only", "-z", "--no-renames", base, sourceSha, "--")
      .split('\u0000').filter { it.isNotEmpty() }
    return MrReviewSnapshot(sourceSha, targetSha, base, files)
  }

  fun content(snapshot: MrReviewSnapshot, path: String): MrReviewFileContent =
    MrReviewFileContent(blob(snapshot.baseSha, path), blob(snapshot.sourceSha, path)).also {
      if (it.before == null && it.after == null) throw IOException("File is absent from both revisions: $path")
    }

  private fun blob(sha: String, path: String): String? {
    val entry = command("ls-tree", "-z", sha, "--", ":(literal)$path")
      .split('\u0000').firstOrNull { it.substringAfter('\t', "") == path } ?: return null
    val fields = entry.substringBefore('\t').split(' ')
    if (fields[1] != "blob") throw IOException("Not a regular Git blob: $path")
    return command("cat-file", "blob", fields[2])
  }

  private fun command(vararg args: String): String {
    val result = GitLocal.runRaw(directory, 30, *args)
    if (!result.ok()) throw IOException(result.stderr.trim().ifBlank { "git ${args.first()} failed (${result.exitCode})" })
    return result.stdout
  }
}
