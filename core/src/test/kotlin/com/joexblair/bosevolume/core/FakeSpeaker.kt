package com.joexblair.bosevolume.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.net.InetAddress

/** A TLS WebSocket server that behaves like a Bose speaker's port 8082 for /audio/volume. */
class FakeSpeaker(
    val guid: String = "GUID-1234",
    var acceptedToken: String = "good-token",
    var volume: Int = 30,
    var muted: Boolean = false,
    val announceOnConnect: Boolean = true,
) : AutoCloseable {
    val server = MockWebServer()
    val received = mutableListOf<JsonObject>()
    var subprotocol: String? = null
    var notBeforeFailures = 0

    init {
        val host = InetAddress.getByName("localhost").canonicalHostName
        val cert = HeldCertificate.Builder().addSubjectAlternativeName(host).build()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
        repeat(10) { server.enqueue(MockResponse().withWebSocketUpgrade(Handler())) }
        server.start()
    }

    val host: String get() = server.hostName
    val port: Int get() = server.port

    private fun volumeBody() = buildJsonObject {
        put("defaultOn", 30); put("max", 100); put("min", 0); put("muted", muted); put("value", volume)
        put("properties", buildJsonObject { put("maxLimit", 80); put("minLimit", 0) })
    }

    private inner class Handler : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            subprotocol = response.request.header("Sec-WebSocket-Protocol")
            if (announceOnConnect) webSocket.send(
                buildJsonObject {
                    put("header", buildJsonObject {
                        put("device", guid); put("msgtype", "NOTIFY"); put("resource", "/connectionState")
                        put("status", 200); put("method", "NOTIFY")
                    })
                    put("body", buildJsonObject { put("state", "connected") })
                }.toString(),
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, null)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val msg = Json.parseToJsonElement(text).jsonObject
            synchronized(received) { received += msg }
            val h = msg["header"]!!.jsonObject
            val reqId = h["reqID"]!!.jsonPrimitive.int
            val resource = h["resource"]!!.jsonPrimitive.content
            fun reply(status: Int, body: JsonObject, error: String? = null) = webSocket.send(
                buildJsonObject {
                    put("header", buildJsonObject {
                        put("device", guid); put("msgtype", "RESPONSE"); put("reqID", reqId)
                        put("resource", resource); put("status", status)
                        put("method", h["method"]!!.jsonPrimitive.content)
                    })
                    put("body", body)
                    if (error != null) put("error", buildJsonObject { put("code", status); put("message", error) })
                }.toString(),
            )
            if (notBeforeFailures > 0) {
                notBeforeFailures--
                reply(401, JsonObject(emptyMap()), "JWT not before check failed"); return
            }
            if (h["token"]!!.jsonPrimitive.content != acceptedToken || h["device"]!!.jsonPrimitive.content != guid) {
                reply(401, JsonObject(emptyMap()), "Unauthorized"); return
            }
            when (resource to h["method"]!!.jsonPrimitive.content) {
                "/audio/volume" to "GET" -> reply(200, volumeBody())
                "/audio/volume" to "PUT" -> {
                    val b = msg["body"]!!.jsonObject
                    b["value"]?.let { volume = it.jsonPrimitive.int }
                    b["muted"]?.let { muted = it.jsonPrimitive.boolean }
                    reply(200, volumeBody())
                }
                else -> reply(404, JsonObject(emptyMap()), "unknown resource")
            }
        }
    }

    override fun close() = server.shutdown()
}
