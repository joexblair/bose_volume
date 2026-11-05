package com.joexblair.bosevolume.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Bose account sign-in, as performed by the Bose Music app.
 *
 * The speakers only accept local WebSocket commands that carry a Bose "control token" (a JWT
 * issued by id.api.bose.io for an account that owns the speaker). Getting one is two steps:
 *
 * 1. Azure AD B2C (myboseid.bose.com) OAuth2 authorization-code + PKCE sign-in, using the Bose
 *    Music app's public client id and its `bosemusic://auth/callback` redirect. The Android app
 *    runs this page in a WebView and catches the redirect, so the password never touches our code.
 * 2. Trade the B2C `id_token` for Bose tokens at id.api.bose.io.
 *
 * The control token is short-lived; we keep the B2C refresh token and repeat both steps
 * silently when it expires.
 */
class BoseAuth(
    private val store: TokenStore,
    private val http: OkHttpClient = OkHttpClient(),
    private val endpoints: Endpoints = Endpoints(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    data class Endpoints(
        val b2cBase: String = "https://myboseid.bose.com/boseprodb2c.onmicrosoft.com",
        val boseIdToken: String = "https://id.api.bose.io/id-jwt-core/idps/aad/$POLICY/token",
    )

    data class Pkce(val verifier: String, val challenge: String) {
        companion object {
            fun generate(random: SecureRandom = SecureRandom()): Pkce {
                val bytes = ByteArray(32).also(random::nextBytes)
                val verifier = b64url(bytes)
                val challenge = b64url(
                    MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
                )
                return Pkce(verifier, challenge)
            }

            private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        }
    }

    private val lock = Mutex()

    val isSignedIn: Boolean get() = store.get(KEY_B2C_REFRESH) != null

    /** URL to load in a browser/WebView. Sign-in ends with a redirect to [REDIRECT_URI]?code=... */
    fun authorizeUrl(pkce: Pkce, locale: String = "en-us"): HttpUrl =
        "${endpoints.b2cBase}/oauth2/v2.0/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("p", POLICY)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", CLIENT_ID)
            .addQueryParameter("scope", SCOPE)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", pkce.challenge)
            .addQueryParameter("redirect_uri", REDIRECT_URI)
            .addQueryParameter("ui_locales", locale)
            .build()

    /** Finish sign-in with the `code` from the redirect. */
    suspend fun completeSignIn(code: String, pkce: Pkce) = lock.withLock {
        val b2c = postForm(
            "${endpoints.b2cBase}/oauth2/v2.0/token?p=$POLICY",
            mapOf(
                "client_id" to CLIENT_ID,
                "code_verifier" to pkce.verifier,
                "grant_type" to "authorization_code",
                "scope" to SCOPE,
                "redirect_uri" to REDIRECT_URI,
                "code" to code,
            ),
        )
        saveB2c(b2c)
        exchangeIdToken(b2c.string("id_token"))
    }

    /**
     * A control token valid for at least another [minValidSeconds], refreshing it if needed.
     * A background heartbeat calls this with a large margin so taps rarely have to wait for it.
     */
    suspend fun controlToken(forceRefresh: Boolean = false, minValidSeconds: Long = 60): String = lock.withLock {
        val cached = store.get(KEY_CONTROL_TOKEN)
        if (!forceRefresh && cached != null && jwtExpiry(cached) > clock() + minValidSeconds) return cached

        val refresh = store.get(KEY_B2C_REFRESH) ?: throw NotSignedInException()
        val b2c = try {
            postForm(
                "${endpoints.b2cBase}/$POLICY/oauth2/v2.0/token",
                mapOf(
                    "refresh_token" to refresh,
                    "client_id" to CLIENT_ID,
                    "grant_type" to "refresh_token",
                ),
            )
        } catch (e: HttpException) {
            if (e.code in 400..499) {
                signOut()
                throw NotSignedInException("Bose sign-in expired; please sign in again")
            }
            throw e
        }
        saveB2c(b2c)
        exchangeIdToken(b2c.string("id_token"))
    }

    fun signOut() {
        store.remove(KEY_B2C_REFRESH)
        store.remove(KEY_CONTROL_TOKEN)
    }

    private fun saveB2c(b2c: JsonObject) {
        b2c.stringOrNull("refresh_token")?.let { store.put(KEY_B2C_REFRESH, it) }
    }

    private suspend fun exchangeIdToken(idToken: String): String {
        val body = buildJsonObject {
            put("grant_type", "id_token")
            put("id_token", idToken)
            put("client_id", CLIENT_ID)
            put("scope", SCOPE)
        }.toString()
        val request = Request.Builder()
            .url(endpoints.boseIdToken)
            .header("X-ApiKey", API_KEY)
            .header("X-Api-Version", "1")
            .header("X-Software-Version", "1")
            .header("X-Library-Version", "1")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val token = execute(request).string("access_token")
        store.put(KEY_CONTROL_TOKEN, token)
        return token
    }

    private suspend fun postForm(url: String, fields: Map<String, String>): JsonObject {
        val form = FormBody.Builder().apply { fields.forEach { (k, v) -> add(k, v) } }.build()
        return execute(Request.Builder().url(url).post(form).build())
    }

    private suspend fun execute(request: Request): JsonObject = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpException(resp.code, "${request.url.encodedPath}: $text")
            Json.parseToJsonElement(text).jsonObject
        }
    }

    private fun JsonObject.string(key: String) =
        stringOrNull(key) ?: throw IllegalStateException("Missing '$key' in token response")

    private fun JsonObject.stringOrNull(key: String) = this[key]?.jsonPrimitive?.content

    companion object {
        // Public identifiers of the Bose Music mobile app.
        const val CLIENT_ID = "e284648d-3009-47eb-8e74-670c5330ae54"
        const val POLICY = "B2C_1A_MBI_SUSI"
        const val REDIRECT_URI = "bosemusic://auth/callback"
        const val SCOPE = "openid email profile offline_access $CLIENT_ID"
        const val API_KEY = "67616C617061676F732D70726F642D6D61647269642D696F73"

        const val KEY_B2C_REFRESH = "b2c_refresh_token"
        const val KEY_CONTROL_TOKEN = "control_token"

        /** `exp` claim of a JWT, or 0 if it can't be read. */
        fun jwtExpiry(jwt: String): Long = runCatching {
            val payload = String(Base64.getUrlDecoder().decode(jwt.split('.')[1].trimEnd('=')))
            Json.parseToJsonElement(payload).jsonObject["exp"]!!.jsonPrimitive.long
        }.getOrDefault(0L)
    }
}

/** Minimal persistent key/value store (SharedPreferences on Android). */
interface TokenStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class InMemoryTokenStore : TokenStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String) = synchronized(map) { map[key] }
    override fun put(key: String, value: String) = synchronized(map) { map[key] = value }
    override fun remove(key: String) { synchronized(map) { map.remove(key) } }
}

class HttpException(val code: Int, message: String) : Exception("HTTP $code $message")
class NotSignedInException(message: String = "Not signed in to a Bose account") : Exception(message)
