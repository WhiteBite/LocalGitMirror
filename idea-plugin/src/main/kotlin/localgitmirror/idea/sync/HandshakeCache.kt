package localgitmirror.idea.sync

import localgitmirror.idea.mirror.MirrorApi
import java.security.MessageDigest

internal object HandshakeCache {
    private const val TTL_MS = 10 * 60 * 1000L

    private data class Key(val baseUrl: String, val passwordHash: String)
    private data class Entry<T>(val value: T, val expiresAt: Long)

    private val lock = Any()
    private val capsCache = HashMap<Key, Entry<MirrorApi.CapabilitiesResult>>()
    private val probeCache = HashMap<Key, Entry<MirrorApi.ProbeResult>>()
    private val pubKeyCache = HashMap<Key, Entry<MirrorApi.PubKeyResult>>()

    private fun hash(password: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(password.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun key(baseUrl: String, syncPassword: String): Key {
        return Key(baseUrl.trimEnd('/'), hash(syncPassword))
    }

    fun capabilities(
        baseUrl: String, apiKey: String, syncPassword: String, insecureTls: Boolean
    ): MirrorApi.CapabilitiesResult {
        val k = key(baseUrl, syncPassword)
        synchronized(lock) {
            val e = capsCache[k]
            if (e != null && e.expiresAt > System.currentTimeMillis()) return e.value
        }
        val r = MirrorApi.capabilities(baseUrl, apiKey, insecureTls)
        if (r.code in 200..299) {
            synchronized(lock) { capsCache[k] = Entry(r, System.currentTimeMillis() + TTL_MS) }
        }
        return r
    }

    fun passwordProbe(
        baseUrl: String, apiKey: String, syncPassword: String, insecureTls: Boolean
    ): MirrorApi.ProbeResult {
        val k = key(baseUrl, syncPassword)
        synchronized(lock) {
            val e = probeCache[k]
            if (e != null && e.expiresAt > System.currentTimeMillis()) return e.value
        }
        val r = MirrorApi.passwordProbe(baseUrl, apiKey, insecureTls)
        if (r.code in 200..299 && r.bytes != null) {
            synchronized(lock) { probeCache[k] = Entry(r, System.currentTimeMillis() + TTL_MS) }
        }
        return r
    }

    fun fetchServerPubKey(
        baseUrl: String, apiKey: String, syncPassword: String, insecureTls: Boolean
    ): MirrorApi.PubKeyResult {
        val k = key(baseUrl, syncPassword)
        synchronized(lock) {
            val e = pubKeyCache[k]
            if (e != null && e.expiresAt > System.currentTimeMillis()) return e.value
        }
        val r = MirrorApi.fetchServerPubKey(baseUrl, apiKey, insecureTls)
        if (r.code in 200..299 && r.pubB64 != null) {
            synchronized(lock) { pubKeyCache[k] = Entry(r, System.currentTimeMillis() + TTL_MS) }
        }
        return r
    }
}
