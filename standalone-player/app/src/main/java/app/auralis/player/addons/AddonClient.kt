package app.auralis.player.addons

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AddonManifest(val name: String, val version: String, val baseUrl: String, val signedRequests: Boolean)
data class AddonTrack(
    val id: String, val title: String, val artist: String, val album: String?,
    val durationMs: Long?, val streamUrl: String?, val mimeType: String?,
    val bitrate: Int?, val sampleRate: Int?
)

/** HTTPS-only transport for compatible public addons and LastWave-signed endpoints. */
class AddonClient(private val baseUrl: String, private val token: String? = null) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
    init { require(Uri.parse(baseUrl).scheme == "https") { "Addon URL must use HTTPS" } }

    suspend fun manifest(): AddonManifest = withContext(Dispatchers.IO) {
        val j = get("/manifest.json")
        AddonManifest(j.optString("name", "Music addon"), j.optString("version", "unknown"),
            baseUrl, j.optBoolean("signedRequests", false))
    }

    suspend fun search(query: String): List<AddonTrack> = withContext(Dispatchers.IO) {
        val j = get("/search?q=" + Uri.encode(query))
        val rows = j.optJSONArray("results") ?: j.optJSONArray("tracks")
            ?: throw IllegalStateException("Addon response has no results or tracks array")
        buildList {
            for (i in 0 until rows.length()) {
                val x = rows.optJSONObject(i) ?: continue
                add(AddonTrack(
                    x.optString("id").ifBlank { x.optString("trackId") },
                    x.optString("title", "Unknown title"), x.optString("artist", "Unknown artist"),
                    x.optString("album").takeIf(String::isNotBlank),
                    x.optLong("duration", -1).takeIf { it > 0 },
                    x.optString("url").takeIf(String::isNotBlank),
                    x.optString("mimeType").takeIf(String::isNotBlank),
                    x.optInt("bitrate", -1).takeIf { it > 0 },
                    x.optInt("sampleRate", -1).takeIf { it > 0 }
                ))
            }
        }
    }

    suspend fun resolve(path: String, method: String = "GET", intent: String = "stream"): String =
        withContext(Dispatchers.IO) {
            val j = get(path, method, intent)
            val url = j.optString("url").ifBlank { j.optString("streamUrl") }
            require(Uri.parse(url).scheme == "https") { "Addon returned a non-HTTPS stream URL" }
            url
        }

    private fun get(path: String, method: String = "GET", intent: String = "stream"): JSONObject {
        require(path.startsWith("/") && !path.startsWith("//")) { "Invalid addon path" }
        val decodedPath = URLDecoder.decode(path.substringBefore('?'), StandardCharsets.UTF_8.name())
        val request = Request.Builder().url(baseUrl.trimEnd('/') + path).get()
        if (!token.isNullOrBlank()) {
            val ts = System.currentTimeMillis().toString()
            request.header("X-LW-TS", ts).header("X-LW-Sign", sign(ts, method, decodedPath, token))
                .header("X-LW-Intent", intent)
        }
        http.newCall(request.build()).execute().use { response ->
            if (!response.isSuccessful) error("Addon HTTP ${response.code}")
            return JSONObject(response.body?.string() ?: "{}")
        }
    }

    private fun sign(ts: String, method: String, path: String, secret: String): String {
        val payload = "$ts\n$method\n$path\n$secret"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
