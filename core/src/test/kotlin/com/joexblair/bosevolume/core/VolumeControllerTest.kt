package com.joexblair.bosevolume.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class VolumeControllerTest {
    @Test fun `refreshes token once when speaker rejects it`() = runBlocking {
        val enc = Base64.getUrlEncoder().withoutPadding()
        fun jwt(n: Int) = enc.encodeToString("{}".toByteArray()) + "." +
            enc.encodeToString("""{"exp":9999999999,"n":$n}""".toByteArray()) + ".s"
        var n = 0
        val cloud = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                    "/bose/token" -> MockResponse().setBody("""{"access_token":"${jwt(n++)}"}""")
                    else -> MockResponse().setBody("""{"id_token":"i","refresh_token":"r"}""")
                }
            }
            start()
        }
        val store = InMemoryTokenStore().apply {
            put(BoseAuth.KEY_B2C_REFRESH, "r")
            put(BoseAuth.KEY_CONTROL_TOKEN, jwt(99)) // valid-looking but revoked
        }
        val auth = BoseAuth(store, endpoints = BoseAuth.Endpoints(cloud.url("/b2c").toString(), cloud.url("/bose/token").toString()))
        FakeSpeaker(acceptedToken = jwt(0)).use { fake ->
            val controller = VolumeController(auth) { t -> BoseSpeaker.connect(fake.host, t, port = fake.port) }
            assertEquals(32, controller.change(2).value)
        }
        cloud.shutdown()
    }
}
