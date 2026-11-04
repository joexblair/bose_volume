package com.joexblair.bosevolume.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BoseSpeakerTest {
    private suspend fun connect(fake: FakeSpeaker, token: String = "good-token", id: String? = null) =
        BoseSpeaker.connect(fake.host, token, deviceId = id, port = fake.port)

    @Test fun `reads volume and learns device id from first message`() = runBlocking {
        FakeSpeaker(volume = 42).use { fake ->
            connect(fake).use { s ->
                val v = s.getVolume()
                assertEquals(BoseSpeaker.Volume(42, 0, 80, false), v) // max honours properties.maxLimit
            }
            assertEquals("eco2", fake.subprotocol)
            val header = fake.received.single()["header"]!!.jsonObject
            assertEquals("GUID-1234", header["device"]!!.jsonPrimitive.content)
            assertEquals("REQUEST", header["msgtype"]!!.jsonPrimitive.content)
            assertEquals("good-token", header["token"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `uses given device id without waiting for announcement`() = runBlocking {
        FakeSpeaker(announceOnConnect = false).use { fake ->
            connect(fake, id = "GUID-1234").use { assertEquals(30, it.getVolume().value) }
        }
    }

    @Test fun `changes volume with clamping`() = runBlocking {
        FakeSpeaker(volume = 78).use { fake ->
            connect(fake).use { s ->
                assertEquals(80, s.changeVolume(5).value)
                assertEquals(80, fake.volume)
                assertEquals(75, s.changeVolume(-5).value)
                assertEquals(0, s.setVolume(-10).value)
            }
            val put = fake.received.first { it["header"]!!.jsonObject["method"]!!.jsonPrimitive.content == "PUT" }
            assertEquals(80, put["body"]!!.jsonObject["value"]!!.jsonPrimitive.int)
        }
    }

    @Test fun `toggles mute`() = runBlocking {
        FakeSpeaker().use { fake ->
            connect(fake).use { s ->
                assertTrue(s.toggleMute().muted)
                assertTrue(fake.muted)
                assertEquals(false, s.toggleMute().muted)
            }
        }
    }

    @Test fun `bad token raises 401`() = runBlocking {
        FakeSpeaker().use { fake ->
            connect(fake, token = "nope").use { s ->
                val e = assertFailsWith<BoseRequestException> { s.getVolume() }
                assertEquals(401, e.status)
            }
        }
    }

    @Test fun `retries on clock-skew not-before`() = runBlocking {
        FakeSpeaker().use { fake ->
            fake.notBeforeFailures = 1
            connect(fake).use { assertEquals(30, it.getVolume().value) }
        }
    }

    @Test fun `unreachable speaker gives connection error`() = runBlocking {
        assertFailsWith<BoseConnectionException> {
            BoseSpeaker.connect("127.0.0.1", "t", "id", port = 1, timeoutMs = 2000)
        }
        Unit
    }
}
