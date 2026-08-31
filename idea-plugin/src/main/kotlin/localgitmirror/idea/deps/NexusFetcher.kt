package localgitmirror.idea.deps

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches Maven artifacts from the corporate Nexus repository.
 * Used when a wanted coordinate is not in local cache — the work machine
 * has network access to Nexus, so it can fill the gap before publishing.
 */
object NexusFetcher {

    data class FetchResult(
        val success: Boolean,
        val bytes: ByteArray?,
        val fileName: String,
        val errorMessage: String = ""
    )

    /**
     * Parsed wanted coordinate from the mirror index response.
     * The server sends `wanted` entries with `maven_path` and optional `coord`.
     * When `coord` is present we use it directly; otherwise we parse from the path.
     */
    data class WantedCoordinate(
        val group: String,
        val artifact: String,
        val version: String,
        val classifier: String,
        val extension: String,
        val mavenPath: String,
        val reason: String
    )

    /**
     * Fetch a single Maven artifact from Nexus.
     *
     * URL pattern:
     *   {nexusBaseUrl}/{group-as-path}/{artifact}/{version}/{artifact}-{version}[-{classifier}].{extension}
     */
    fun fetch(
        nexusBaseUrl: String,
        group: String,
        artifact: String,
        version: String,
        classifier: String,
        extension: String
    ): FetchResult {
        return try {
            val groupPath = group.replace('.', '/')
            val classifierSuffix = if (classifier.isNotEmpty()) "-$classifier" else ""
            val fileName = "$artifact-$version$classifierSuffix.$extension"
            val url = "${nexusBaseUrl.trimEnd('/')}/$groupPath/$artifact/$version/$fileName"

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "*/*")

            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return FetchResult(false, null, fileName, "HTTP $code")
            }

            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            FetchResult(true, bytes, fileName)
        } catch (e: Throwable) {
            val classifierSuffix = if (classifier.isNotEmpty()) "-$classifier" else ""
            val fileName = "$artifact-$version$classifierSuffix.$extension"
            FetchResult(false, null, fileName, e.message ?: "unknown")
        }
    }

    /**
     * Fetch all artifacts for a list of wanted coordinates.
     * Returns map of mavenPath -> FetchResult.
     * Sequential by design — ponytail: parallel only if throughput matters.
     */
    fun fetchAll(
        nexusBaseUrl: String,
        wanted: List<WantedCoordinate>
    ): Map<String, FetchResult> {
        val results = LinkedHashMap<String, FetchResult>()
        for (w in wanted) {
            results[w.mavenPath] = fetch(
                nexusBaseUrl = nexusBaseUrl,
                group = w.group,
                artifact = w.artifact,
                version = w.version,
                classifier = w.classifier,
                extension = w.extension
            )
        }
        return results
    }

    /**
     * Write fetched bytes to a temp file. Returns the temp file or null on failure.
     */
    fun writeToTemp(fetchResult: FetchResult): File? {
        val bytes = fetchResult.bytes ?: return null
        return try {
            val tmp = File.createTempFile("lgm-nexus-", "-${fetchResult.fileName}")
            tmp.deleteOnExit()
            FileOutputStream(tmp).use { it.write(bytes) }
            tmp
        } catch (_: Throwable) {
            null
        }
    }
}