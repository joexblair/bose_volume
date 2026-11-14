package com.joexblair.bosevolume

import android.content.Context
import android.content.SharedPreferences
import com.joexblair.bosevolume.core.BoseAuth
import com.joexblair.bosevolume.core.BoseSpeaker
import com.joexblair.bosevolume.core.TokenStore
import com.joexblair.bosevolume.core.VolumeController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The chosen speaker. */
data class SpeakerConfig(val host: String, val guid: String?, val name: String)

/** Process-wide wiring: preferences, auth and the volume controller. */
object Bose {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Serialises volume commands so rapid taps don't read the same starting volume. */
    private val commandLock = Mutex()

    @Volatile private var auth: BoseAuth? = null

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("bose", Context.MODE_PRIVATE)

    fun auth(context: Context): BoseAuth =
        auth ?: synchronized(this) {
            auth ?: BoseAuth(PrefsTokenStore(prefs(context))).also { auth = it }
        }

    fun speaker(context: Context): SpeakerConfig? {
        val p = prefs(context)
        val host = p.getString("host", null) ?: return null
        return SpeakerConfig(host, p.getString("guid", null), p.getString("name", null) ?: host)
    }

    fun setSpeaker(context: Context, speaker: SpeakerConfig) {
        prefs(context).edit()
            .putString("host", speaker.host)
            .putString("guid", speaker.guid)
            .putString("name", speaker.name)
            .apply()
    }

    fun step(context: Context): Int = prefs(context).getInt("step", 3)
    fun setStep(context: Context, step: Int) = prefs(context).edit().putInt("step", step.coerceIn(1, 20)).apply()

    /** Run a volume command against the configured speaker. */
    suspend fun <T> command(context: Context, op: suspend (VolumeController) -> T): T {
        val speaker = speaker(context) ?: throw NoSpeakerException()
        val controller = VolumeController(auth(context)) { token ->
            BoseSpeaker.connect(speaker.host, token, speaker.guid)
        }
        return commandLock.withLock { op(controller) }
    }
}

class NoSpeakerException : Exception("No speaker selected")

private class PrefsTokenStore(private val prefs: SharedPreferences) : TokenStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}
