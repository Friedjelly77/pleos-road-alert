package kr.roadalert

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** One encrypted commit holds the participant capability, outbox and last acknowledgement. */
class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("private_state", Context.MODE_PRIVATE)
    private val alias = "road-alert-session-v1"
    private val key: SecretKey
    private val state: JSONObject
    init {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        key = store.getKey(alias, null) as? SecretKey ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
        val encoded = prefs.getString("state", null)
        if (encoded == null) {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            state = JSONObject().put("credential", bytes.joinToString("") { "%02x".format(it) })
                .put("pending", JSONArray()).put("seen", JSONObject())
            save()
        } else {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            state = JSONObject(String(cipher.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8))
        }
    }

    private fun save() {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val blob = cipher.iv + cipher.doFinal(state.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("state", Base64.encodeToString(blob, Base64.NO_WRAP)).commit())
    }
    val credential: String get() = state.getString("credential")
    val pending: JSONArray get() = state.getJSONArray("pending")
    val lastId: String get() = state.optString("lastId")
    fun enqueue(path: String, method: String, payload: JSONObject): Boolean {
        if (pending.length() >= 12) return false
        val item = JSONObject().put("id", UUID.randomUUID().toString()).put("path", path)
            .put("method", method).put("body", payload)
        pending.put(item)
        save()
        return true
    }
    fun complete(id: String, event: JSONObject?) {
        val i = (0 until pending.length()).firstOrNull { pending.getJSONObject(it).getString("id") == id } ?: return
        pending.remove(i)
        if (event != null) state.put("lastId", event.getString("id"))
        save()
    }
    fun firstAlert(id: String, expiresAt: Long, now: Long): Boolean {
        val seen = state.getJSONObject("seen")
        seen.keys().asSequence().toList().forEach { if (seen.getLong(it) <= now) seen.remove(it) }
        val first = !seen.has(id)
        if (first || seen.getLong(id) < expiresAt) { seen.put(id, expiresAt); save() }
        return first
    }
}

class ApiError(val status: Int) : IOException("HTTP $status")

class Api(private val key: String) {
    fun call(path: String, method: String, body: JSONObject? = null): JSONObject {
        val conn = URL("http://127.0.0.1:8787$path").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 3000
            conn.readTimeout = 11500
            conn.useCaches = false
            conn.setRequestProperty("Authorization", "Bearer $key")
            if (body != null) {
                val bytes = body.toString().toByteArray(Charsets.UTF_8)
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            if (status !in 200..299) throw ApiError(status)
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally { conn.disconnect() }
    }
}

interface SyncView {
    fun onWatch(reply: JSONObject?, receivedAt: Long)
    fun onDelivery(message: String, reply: JSONObject?)
}

/** Workers perform network I/O. Snapshot selection, durable queue and UI callbacks share the main thread. */
class Sync(private val vault: Vault, private val view: SyncView, private val position: () -> RoadFix?) {
    private val api = Api(vault.credential)
    private val main = Handler(Looper.getMainLooper())
    private val watches = Executors.newFixedThreadPool(2)
    private val writes = Executors.newSingleThreadExecutor()
    private val reads = Executors.newSingleThreadExecutor()
    private var active = false
    private var epoch = 0
    private var writing = false
    private var destroyed = false
    private var retryMs = 1000L
    private var retryAt = 0L
    private var watchRetryMs = 1000L
    private var cursor = ""

    fun start() {
        active = true
        newPosition()
        flush()
    }
    fun stop() { active = false; epoch++ }
    fun destroy() { destroyed = true; stop(); watches.shutdownNow(); writes.shutdownNow(); reads.shutdownNow() }
    fun newPosition() {
        cursor = ""
        watchRetryMs = 1000
        epoch++
        if (active) watch(epoch)
    }
    private fun watch(generation: Int) {
        if (!active || epoch != generation) return
        val fix = position() ?: return
        val req = JSONObject().put("fix", fix.json()).put("waitMs", 6000).put("cursor", cursor)
        watches.execute {
            val result = runCatching { api.call("/v1/watch", "POST", req) }.getOrNull()
            val received = System.currentTimeMillis()
            main.post {
                if (!active || epoch != generation) return@post
                view.onWatch(result, received)
                if (result != null) { cursor = result.getString("cursor"); watchRetryMs = 1000 }
                val wait = when {
                    result == null -> watchRetryMs.also { watchRetryMs = (it * 2).coerceAtMost(8000) }
                    !result.getBoolean("fresh") -> 2000L
                    else -> 50L
                }
                main.postDelayed({ watch(generation) }, wait)
                if (result != null) flush()
            }
        }
    }

    fun submit(path: String, method: String, payload: JSONObject) {
        if (!vault.enqueue(path, method, payload)) { view.onDelivery("대기 중인 제보가 가득 찼습니다", null); return }
        view.onDelivery("전송 대기 · 서버 접수 확인 중", null)
        flush()
    }
    private fun flush() {
        if (!active || writing || vault.pending.length() == 0) return
        val delay = retryAt - SystemClock.elapsedRealtime()
        if (delay > 0) { main.postDelayed({ flush() }, delay); return }
        writing = true
        val item = JSONObject(vault.pending.getJSONObject(0).toString())
        writes.execute {
            val result = runCatching {
                val data = item.getJSONObject("body")
                if (data.has("requestId")) data.put("sentAt", System.currentTimeMillis())
                api.call(item.getString("path"), item.getString("method"), data)
            }
            main.post {
                if (destroyed) return@post
                writing = false
                val reply = result.getOrNull()
                val error = result.exceptionOrNull() as? ApiError
                if (reply != null || (error != null && error.status in 400..499 && error.status !in listOf(408, 429))) {
                    vault.complete(item.getString("id"), reply?.getJSONObject("event"))
                    retryAt = 0; retryMs = 1000
                    if (active) {
                        val message = if (reply != null) "서버 접수 완료" else when (error?.status) {
                            422 -> "접수되지 않았습니다 · 위치 또는 제보 시각 확인"
                            409 -> "접수되지 않았습니다 · 사건 상태 확인"
                            else -> "접수되지 않았습니다 · 권한 또는 사건 확인"
                        }
                        view.onDelivery(message, reply)
                    }
                    flush()
                } else {
                    retryAt = SystemClock.elapsedRealtime() + retryMs
                    retryMs = (retryMs * 2).coerceAtMost(15000)
                    if (active) { view.onDelivery("연결 끊김 · 저장 후 재시도 중", null); flush() }
                }
            }
        }
    }
    fun detail(id: String, done: (JSONObject?) -> Unit) {
        val generation = epoch
        reads.execute {
            val result = runCatching { api.call("/v1/events/$id", "GET") }.getOrNull()
            main.post { if (active && epoch == generation) done(result) }
        }
    }
}
