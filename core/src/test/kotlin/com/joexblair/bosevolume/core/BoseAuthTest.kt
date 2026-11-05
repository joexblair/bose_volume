package com.joexblair.bosevolume.core

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoseAuthTest {
    private val server = MockWebServer()
    private val requests = mutableListOf<Pair<String, String>>()
    private var now = 1_000_000L
    private var issued = 0
    private var refreshAllowed = true

    private fun jwt(exp: Long): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("{}".toByteArray()) + "." +
            enc.encodeToString("""{"exp":$exp,"n":${issued++}}""".toByteArray()) + ".sig"
    }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                synchronized(requests) { requests += request.path!! to body }
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/b2c/oauth2/v2.0/token" ->
                        MockResponse().setBody("""{"id_token":"idtok-code","refresh_token":"r1"}""")
                    path == "/b2c/${BoseAuth.POLICY}/oauth2/v2.0/token" ->
                        if (refreshAllowed) MockResponse().setBody("""{"id_token":"idtok-refresh","refresh_token":"r2"}""")
                        else MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}""")
                    path == "/bose/token" -> {
                        check(request.getHeader("X-ApiKey") == BoseAuth.API_KEY)
                        MockResponse().setBody("""{"access_token":"${jwt(now + 3600)}","bosePersonID":"p"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private val store = InMemoryTokenStore()
    private val auth = BoseAuth(
        store,
        endpoints = BoseAuth.Endpoints(server.url("/b2c").toString(), server.url("/bose/token").toString()),
        clock = { now },
    )

    @AfterTest fun tearDown() = server.shutdown()

    @Test fun `authorize url carries pkce and app redirect`() {
        val pkce = BoseAuth.Pkce.generate()
        val url = auth.authorizeUrl(pkce)
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(pkce.challenge, url.queryParameter("code_challenge"))
        assertEquals("bosemusic://auth/callback", url.queryParameter("redirect_uri"))
        assertEquals(BoseAuth.CLIENT_ID, url.queryParameter("client_id"))
    }

    @Test fun `sign in, cache, then refresh when expired`() = runBlocking {
        assertFalse(auth.isSignedIn)
        assertFailsWith<NotSignedInException> { auth.controlToken() }

        val pkce = BoseAuth.Pkce.generate()
        val t1 = auth.completeSignIn("the-code", pkce)
        assertTrue(auth.isSignedIn)
        assertTrue(requests[0].second.contains("code_verifier=${pkce.verifier}"))
        assertTrue(requests[0].second.contains("code=the-code"))
        assertTrue(requests[1].second.contains("\"id_token\":\"idtok-code\""))

        assertEquals(t1, auth.controlToken()) // cached, no network
        assertEquals(2, requests.size)

        now += 3600 // expired
        val t2 = auth.controlToken()
        assertTrue(t2 != t1)
        assertTrue(requests[2].second.contains("refresh_token=r1"))
        assertTrue(requests[3].second.contains("idtok-refresh"))
        assertEquals("r2", store.get(BoseAuth.KEY_B2C_REFRESH))
    }

    @Test fun `heartbeat margin refreshes early, normal calls don't`() = runBlocking {
        val t1 = auth.completeSignIn("c", BoseAuth.Pkce.generate()) // expires at now + 3600
        now += 3600 - 600 // 10 minutes left
        assertEquals(t1, auth.controlToken()) // still fine for a tap
        assertEquals(2, requests.size)
        val t2 = auth.controlToken(minValidSeconds = 20 * 60) // heartbeat wants 20 minutes
        assertTrue(t2 != t1)
        assertEquals(4, requests.size)
        assertEquals(t2, auth.controlToken(minValidSeconds = 20 * 60)) // now fresh, no new call
        assertEquals(4, requests.size)
    }

    @Test fun `revoked refresh token signs out`() = runBlocking {
        auth.completeSignIn("c", BoseAuth.Pkce.generate())
        refreshAllowed = false
        assertFailsWith<NotSignedInException> { auth.controlToken(forceRefresh = true) }
        assertFalse(auth.isSignedIn)
    }

    @Test fun `jwt expiry parsing`() {
        assertEquals(now + 5, BoseAuth.jwtExpiry(jwt(now + 5)))
        assertEquals(0, BoseAuth.jwtExpiry("garbage"))
    }
}
