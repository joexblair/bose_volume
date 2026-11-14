package com.joexblair.bosevolume.core

/**
 * One-shot volume operations against a configured speaker: get a valid control token, open the
 * socket, run [block], close. A rejected token (401, not the clock-skew case) is refreshed once.
 */
class VolumeController(
    private val auth: BoseAuth,
    private val connector: suspend (token: String) -> BoseSpeaker,
) {
    constructor(auth: BoseAuth, host: String, deviceId: String?) :
        this(auth, { token -> BoseSpeaker.connect(host, token, deviceId) })

    suspend fun <T> withSpeaker(block: suspend (BoseSpeaker) -> T): T {
        try {
            return run(auth.controlToken(), block)
        } catch (e: BoseRequestException) {
            if (e.status != 401) throw e
        }
        return run(auth.controlToken(forceRefresh = true), block)
    }

    private suspend fun <T> run(token: String, block: suspend (BoseSpeaker) -> T): T =
        connector(token).use { block(it) }

    suspend fun get() = withSpeaker { it.getVolume() }
    suspend fun change(delta: Int) = withSpeaker { it.changeVolume(delta) }
    suspend fun set(value: Int) = withSpeaker { it.setVolume(value) }
    suspend fun toggleMute() = withSpeaker { it.toggleMute() }
}
