package dev.nulltogeek.codexremote

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.io.File

class SessionExpiredException : IOException("Your session expired. Please sign in again.")
class RpcException(val code: Int, message: String) : IOException(message)
class HostHttpException(val statusCode: Int) : IOException("Host returned HTTP $statusCode")
data class LoginSession(val token: String, val username: String, val expiresAt: Long)

class RpcClient {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS).build()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject>>()
    val events = Channel<JsonObject>(1024)
    val status = MutableStateFlow("Offline")
    @Volatile var epoch = ""; private set
    @Volatile var sequence = 0L; private set
    private var socket: WebSocket? = null
    private var opened: CompletableDeferred<Unit>? = null
    private var endpoint = ""; private var token = ""

    fun configure(endpoint: String, token: String = "") {
        val url = endpoint.toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid HTTPS host URL")
        val localDebug = BuildConfig.DEBUG && url.host in listOf("127.0.0.1", "localhost", "10.0.2.2")
        require(url.scheme == "https" || localDebug) { "Use HTTPS for your private host" }
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.encodedPath == "/" && url.fragment == null) { "Use a host URL without credentials, path or query" }
        this.endpoint = endpoint.trimEnd('/'); this.token = token
    }
    suspend fun login(endpoint: String, username: String, password: String): LoginSession = withContext(Dispatchers.IO) {
        configure(endpoint)
        require(username.isNotBlank() && password.isNotEmpty()) { "Enter a username and password" }
        val body = buildJsonObject { put("username", username); put("password", password) }
            .toString().toRequestBody("application/json".toMediaType())
        http.newCall(Request.Builder().url(this@RpcClient.endpoint + "/v1/login").post(body).build()).execute().use {
            if (!it.isSuccessful) throw IOException(when (it.code) {
                401 -> "Username or password was rejected"
                429 -> "Too many login attempts. Try again later."
                else -> "Sign-in failed (HTTP ${it.code})"
            })
            val result = WireJson.parseToJsonElement(it.body?.string() ?: "{}").jsonObject
            val session = LoginSession(result.text("token"), result.text("username"), result["expiresAt"]?.jsonPrimitive?.longOrNull ?: 0)
            require(session.token.length == 43 && session.expiresAt > System.currentTimeMillis()) { "Invalid sign-in response" }
            token = session.token; epoch = ""; sequence = 0
            while (events.tryReceive().isSuccess) { }
            session
        }
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(endpoint + "/v1/logout").header("Authorization", "Bearer $token")
            .post("{}".toRequestBody("application/json".toMediaType())).build()).execute().use {
            if (!it.isSuccessful && it.code != 401) throw IOException("Sign-out failed (HTTP ${it.code})")
        }
    }
    suspend fun changePassword(currentPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        val body = jsonObject("currentPassword" to currentPassword, "newPassword" to newPassword)
            .toString().toRequestBody("application/json".toMediaType())
        http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(Request.Builder().url(endpoint + "/v1/password").header("Authorization", "Bearer $token").post(body).build())
            .execute().use {
                if (it.code == 401) throw SessionExpiredException()
                if (!it.isSuccessful) throw IOException(when (it.code) {
                    400 -> "Use a different password with at least 12 characters and at most 1024 bytes."
                    403 -> "Current password was rejected."
                    429 -> "Too many password attempts. Try again later."
                    409 -> "Another password change occurred. Try again."
                    404, 405, 501 -> "Update the computer's server to enable password changes."
                    else -> "Password change failed (HTTP ${it.code})."
                })
                require(WireJson.parseToJsonElement(it.body?.string() ?: "{}").jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true) { "Invalid password change response" }
            }
    }
    suspend fun get(path: String): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(endpoint + path).header("Authorization", "Bearer $token").build()).execute().use {
            if (it.code == 401) throw SessionExpiredException()
            if (!it.isSuccessful) throw HostHttpException(it.code)
            WireJson.parseToJsonElement(it.body?.string() ?: "{}").jsonObject
        }
    }
    suspend fun upload(name: String, mimeType: String, bytes: ByteArray, progress: (Int) -> Unit = {}): Attachment = withContext(Dispatchers.IO) {
        require(bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024) { "Select a non-empty file up to 20 MB" }
        val body = object : RequestBody() {
            override fun contentType() = mimeType.toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: okio.BufferedSink) {
                var offset = 0; var lastPercent = -1
                progress(0)
                while (offset < bytes.size) {
                    val count = minOf(64 * 1024, bytes.size - offset)
                    sink.write(bytes, offset, count); offset += count
                    val percent = (100L * offset / bytes.size).toInt()
                    if (percent != lastPercent) { progress(percent); lastPercent = percent }
                }
            }
        }
        val call = http.newBuilder().writeTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
            .newCall(Request.Builder().url(endpoint + "/v1/attachments").header("Authorization", "Bearer $token")
                .header("X-File-Name", URLEncoder.encode(name, "UTF-8").replace("+", "%20")).post(body).build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use {
                        if (it.code == 401) throw SessionExpiredException()
                        if (!it.isSuccessful) throw IOException(if (it.code == 413) "File is larger than 20 MB" else "Attachment upload failed (HTTP ${it.code})")
                        parseAttachment(WireJson.parseToJsonElement(it.body?.string() ?: "{}").jsonObject)
                    } })
                }
            })
        }
    }
    suspend fun downloadAttachmentImage(path: String): ByteArray = withContext(Dispatchers.IO) {
        if (path.startsWith("data:")) {
            val prefix = Regex("^data:image/(png|jpeg|webp|gif);base64,").find(path)
                ?: throw IllegalArgumentException("Image preview unavailable")
            val encoded = path.substring(prefix.value.length)
            require(encoded.length <= ((20 * 1024 * 1024 + 2) / 3) * 4) { "Image preview unavailable" }
            return@withContext java.util.Base64.getDecoder().decode(encoded).also {
                require(it.isNotEmpty() && it.size <= 20 * 1024 * 1024) { "Image preview unavailable" }
            }
        }
        require(path.startsWith("/") && path.length <= 4096 && !path.contains('\u0000')) { "Image preview unavailable" }
        val url = (endpoint + "/v1/attachments/image").toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("path", path).build()
        // Only the configured host receives the session, including when a server returns a redirect.
        val call = http.newBuilder().followRedirects(false).followSslRedirects(false).build()
            .newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { continuation.resumeWith(Result.failure(e)) }
                override fun onResponse(call: Call, response: Response) {
                    continuation.resumeWith(runCatching { response.use {
                        if (it.code == 401) throw SessionExpiredException()
                        if (it.code != 200) throw IOException("Image preview unavailable")
                        val body = it.body ?: throw IOException("Image preview unavailable")
                        val type = body.contentType()?.let { mime -> "${mime.type}/${mime.subtype}" }
                        require(type in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) { "Image preview unavailable" }
                        require(body.contentLength() <= 20 * 1024 * 1024) { "Image preview unavailable" }
                        body.byteStream().use { input -> readAttachmentBytes(input) }
                    } })
                }
            })
        }
    }
    suspend fun downloadAppUpdate(update: AppUpdate, directory: File, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        // Keep session headers on this configured host; update metadata cannot redirect downloads.
        http.newBuilder().followRedirects(false).followSslRedirects(false).callTimeout(15, TimeUnit.MINUTES).build()
            .newCall(Request.Builder().url(endpoint + update.downloadPath).header("Authorization", "Bearer $token").build()).execute().use {
                if (it.code == 401) throw SessionExpiredException()
                if (it.code != 200) throw IOException("Update download failed (HTTP ${it.code}). Check for updates and retry.")
                val body = it.body ?: throw IOException("Empty update download")
                if (body.contentLength() >= 0 && body.contentLength() != update.size) throw IOException("Update size did not match")
                body.byteStream().use { input -> receiveAppUpdate(input, directory, update, progress) }
            }
    }
    suspend fun connect(): JsonObject {
        close(); status.value = "Connecting"; val ready = CompletableDeferred<Unit>(); opened = ready
        val request = Request.Builder().url(endpoint.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/v1/rpc")
            .header("Authorization", "Bearer $token").build()
        socket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { ready.complete(Unit) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (webSocket !== socket) return
                try {
                    val message = WireJson.parseToJsonElement(text).jsonObject
                    val id = (message["id"] as? JsonPrimitive)?.longOrNull
                    if (id != null) {
                        val waiting = pending.remove(id)
                        if (message["error"] != null) waiting?.completeExceptionally(RpcException(message.obj("error")["code"]?.jsonPrimitive?.intOrNull ?: -1, message.obj("error").text("message", "Request failed")))
                        else waiting?.complete(JsonObject(message.obj("result") + mapOf("_bridgeSequence" to (message["bridgeSequence"] ?: JsonPrimitive(0)))))
                    } else if (message.text("type") == "event") {
                        val next = (message["sequence"] as? JsonPrimitive)?.longOrNull ?: 0
                        val incomingEpoch = message.text("epoch")
                        if (incomingEpoch == epoch && next <= sequence) return
                        val previousEpoch = epoch; val previousSequence = sequence
                        // A receiver may resume inside trySend. Publish its reconnect
                        // cursor first, but keep undelivered events eligible for replay.
                        epoch = incomingEpoch; sequence = next
                        if (!events.trySend(JsonObject(message.obj("message") + mapOf("_bridgeSequence" to JsonPrimitive(next), "_bridgeEpoch" to JsonPrimitive(incomingEpoch)))).isSuccess) {
                            epoch = previousEpoch; sequence = previousSequence
                            webSocket.close(1013, "Resynchronize"); return
                        }
                    }
                } catch (_: Exception) { webSocket.close(1002, "Invalid host message") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (webSocket === socket) fail("Connection lost", response?.code == 401) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (webSocket === socket) fail("Offline", code == 1008) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
        })
        withTimeout(20000) { ready.await() }
        call("initialize", buildJsonObject {})
        socket?.send("{\"method\":\"initialized\",\"params\":{}}")
        val snapshot = call("bridge/subscribe", buildJsonObject { put("epoch", epoch); put("sequence", sequence) })
        val cursor = subscriptionCursor(epoch, sequence, snapshot)
        epoch = cursor.first; sequence = cursor.second
        status.value = "Connected"; return snapshot
    }
    suspend fun call(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val id = ids.incrementAndGet(); val result = CompletableDeferred<JsonObject>(); pending[id] = result
        try {
            val message = buildJsonObject { put("id", id); put("method", method); put("params", params) }
            if (socket?.send(message.toString()) != true) throw IOException("Not connected")
            return withTimeout(30000) { result.await() }
        } finally { pending.remove(id) }
    }
    private fun fail(message: String, expired: Boolean = false) {
        status.value = if (expired) "Session expired" else message
        opened?.completeExceptionally(if (expired) SessionExpiredException() else IOException(message))
        pending.values.forEach { it.completeExceptionally(IOException("Connection lost; check chat history before resending")) }; pending.clear()
    }
    fun close() { socket?.close(1000, "Client paused"); socket = null; fail("Offline") }
}
