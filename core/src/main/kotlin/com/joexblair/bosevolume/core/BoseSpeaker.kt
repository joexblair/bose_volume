package com.joexblair.bosevolume.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Local control channel of a Bose Music speaker/soundbar.
 *
 * The Bose Music app talks to the speaker over `wss://<ip>:8082` (sub-protocol `eco2`, self-signed
 * certificate). Every message is JSON:
 *
 * ```
 * {"header": {"device": "<GUID>", "method": "GET|PUT|POST", "msgtype": "REQUEST",
 *             "reqID": 7, "resource": "/audio/volume", "status": 200,
 *             "token": "<control token>", "version": 1},
 *  "body": {...}}
 * ```
 *
 * Responses echo `reqID` with `msgtype: "RESPONSE"`; anything else is a notification.
 */
class BoseSpeaker private constructor(
    private val socket: WebSocket,
    private val listener: Listener,
    private val token: String,
    private val deviceId: String,
) : AutoCloseable {

    data class Volume(val value: Int, val min: Int, val max: Int, val muted: Boolean) {
        fun clamp(v: Int) = v.coerceIn(min, max)
    }

    private val reqIds = AtomicInteger(1)

    /** Send a request and wait for its response body. */
    suspend fun request(
        resource: String,
        method: String = "GET",
        body: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = 8_000,
    ): JsonObject {
        repeat(NOT_BEFORE_RETRIES) {
            try {
                return requestOnce(resource, method, body, timeoutMs)
            } catch (e: BoseRequestException) {
                // A speaker whose clock is slightly behind rejects a fresh token until it passes nbf.
                if (e.status != 401 || !e.message.orEmpty().contains("not before", ignoreCase = true)) throw e
                delay(2_000)
            }
        }
        return requestOnce(resource, method, body, timeoutMs)
    }

    private suspend fun requestOnce(resource: String, method: String, body: JsonObject, timeoutMs: Long): JsonObject {
        val id = reqIds.getAndIncrement()
        val message = buildJsonObject {
            put("header", buildJsonObject {
                put("device", deviceId)
                put("method", method)
                put("msgtype", "REQUEST")
                put("reqID", id)
                put("resource", resource)
                put("status", 200)
                put("token", token)
                put("version", 1)
            })
            put("body", body)
        }
        val pending = CompletableDeferred<JsonObject>()
        listener.pending[id] = pending
        try {
            if (!socket.send(message.toString())) throw BoseConnectionException("WebSocket closed")
            val response = withTimeout(timeoutMs) { pending.await() }
            val header = response["header"]!!.jsonObject
            val status = header["status"]?.jsonPrimitive?.intOrNull ?: 999
            if (status != 200) {
                val err = response["error"]?.jsonObject
                throw BoseRequestException(
                    resource, status,
                    err?.get("message")?.jsonPrimitive?.content ?: response.toString(),
                )
            }
            return response["body"]?.jsonObject ?: JsonObject(emptyMap())
        } finally {
            listener.pending.remove(id)
        }
    }

    suspend fun getVolume(): Volume = parseVolume(request("/audio/volume"))

    suspend fun setVolume(value: Int): Volume {
        val current = getVolume()
        val body = buildJsonObject { put("value", current.clamp(value)) }
        return parseVolume(request("/audio/volume", "PUT", body), fallback = current.copy(value = current.clamp(value)))
    }

    suspend fun changeVolume(delta: Int): Volume {
        val current = getVolume()
        val target = current.clamp(current.value + delta)
        if (target == current.value) return current
        val body = buildJsonObject { put("value", target) }
        return parseVolume(request("/audio/volume", "PUT", body), fallback = current.copy(value = target))
    }

    suspend fun setMuted(muted: Boolean): Volume {
        val body = buildJsonObject { put("muted", muted) }
        val resp = request("/audio/volume", "PUT", body)
        return if (resp.containsKey("value")) parseVolume(resp) else getVolume()
    }

    suspend fun toggleMute(): Volume = setMuted(!getVolume().muted)

    override fun close() {
        socket.close(1000, null)
    }

    internal class Listener : WebSocketListener() {
        val opened = CompletableDeferred<Unit>()
        val deviceId = CompletableDeferred<String>()
        val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            val header = msg["header"]?.jsonObject ?: return
            header["device"]?.jsonPrimitive?.content?.let { deviceId.complete(it) }
            if (header["msgtype"]?.jsonPrimitive?.content == "RESPONSE") {
                val id = header["reqID"]?.jsonPrimitive?.intOrNull ?: return
                pending[id]?.complete(msg)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail(BoseConnectionException("Speaker connection failed: ${t.message}", t))
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            fail(BoseConnectionException("Speaker closed the connection ($code $reason)"))
        }

        private fun fail(e: Exception) {
            opened.completeExceptionally(e)
            deviceId.completeExceptionally(e)
            pending.values.forEach { it.completeExceptionally(e) }
        }
    }

    companion object {
        const val PORT = 8082
        const val SUBPROTOCOL = "eco2"
        /** Product identifier the official app sends; the speaker expects one to be present. */
        const val PRODUCT = "Madrid-iOS:31019F02-F01F-4E73-B495-B96D33AD3664"
        private const val NOT_BEFORE_RETRIES = 3

        fun url(host: String, port: Int = PORT) = "wss://$host:$port/?product=$PRODUCT"

        /**
         * Open a control connection.
         *
         * @param deviceId the speaker GUID (from mDNS `_bose-passport._tcp` TXT `GUID`). If null,
         *   it is taken from the first message the speaker sends after connecting.
         */
        suspend fun connect(
            host: String,
            token: String,
            deviceId: String? = null,
            port: Int = PORT,
            client: OkHttpClient = sharedSpeakerClient,
            timeoutMs: Long = 6_000,
        ): BoseSpeaker {
            val listener = Listener()
            val request = Request.Builder()
                .url(url(host, port))
                .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
                .build()
            val socket = client.newWebSocket(request, listener)
            return try {
                withTimeout(timeoutMs) {
                    listener.opened.await()
                    BoseSpeaker(socket, listener, token, deviceId ?: listener.deviceId.await())
                }
            } catch (e: Throwable) {
                socket.cancel()
                throw if (e is BoseConnectionException) e else BoseConnectionException("Couldn't reach speaker at $host: ${e.message}", e)
            }
        }

        /** Reused across commands so each tap doesn't rebuild the TLS setup and thread pools. */
        val sharedSpeakerClient: OkHttpClient by lazy { speakerHttpClient() }

        /**
         * OkHttp client for the speaker only. Speakers present a self-signed certificate for their
         * IP address, so certificate and hostname checks are disabled on this client. Never use it
         * for internet traffic.
         */
        fun speakerHttpClient(): OkHttpClient {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
            return OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, trustAll)
                .hostnameVerifier { _, _ -> true }
                .connectTimeout(4, TimeUnit.SECONDS)
                .pingInterval(0, TimeUnit.SECONDS)
                .build()
        }

        internal fun parseVolume(body: JsonObject, fallback: Volume? = null): Volume {
            val value = body["value"]?.jsonPrimitive?.intOrNull
                ?: return fallback ?: throw IllegalStateException("No volume in response: $body")
            val maxLimit = body["properties"]?.jsonObject?.get("maxLimit")?.jsonPrimitive?.intOrNull
            val max = listOfNotNull(body["max"]?.jsonPrimitive?.intOrNull ?: 100, maxLimit).min()
            return Volume(
                value = value,
                min = body["min"]?.jsonPrimitive?.intOrNull ?: 0,
                max = max,
                muted = body["muted"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
}

class BoseRequestException(val resource: String, val status: Int, message: String) :
    Exception("$resource failed ($status): $message")

class BoseConnectionException(message: String, cause: Throwable? = null) : Exception(message, cause)
