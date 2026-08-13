package com.xingzhi.remoteconfig

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteConfigClientTest {
    @Test
    fun `commits only after decode and validation succeed`() = runTest {
        val events = mutableListOf<String>()
        val snapshot = ConfigSnapshot("new".encodeToByteArray(), "sig".encodeToByteArray())
        val store = RecordingStore(events)
        val client = RemoteConfigClient(
            source = ConfigSource { events += "fetch"; snapshot },
            store = store,
            decoder = ConfigDecoder { events += "decode"; it.decodeToString() },
            validator = ConfigValidator { _, value -> events += "validate:$value" },
        )

        val result = client.refresh("device")

        assertEquals("new", result.getOrThrow().value)
        assertEquals(listOf("fetch", "decode", "validate:new", "save"), events)
        assertEquals(snapshot, store.snapshot)
    }

    @Test
    fun `validation failure keeps last known good snapshot`() = runTest {
        val old = ConfigSnapshot("old".encodeToByteArray())
        val store = RecordingStore(snapshot = old)
        val client = RemoteConfigClient(
            source = ConfigSource { ConfigSnapshot("bad".encodeToByteArray()) },
            store = store,
            decoder = ConfigDecoder(ByteArray::decodeToString),
            validator = ConfigValidator { _, _ -> error("invalid signature") },
        )

        val result = client.refresh("device")

        assertIs<RemoteConfigException.ValidationFailed>(result.exceptionOrNull())
        assertEquals(old, store.snapshot)
    }

    @Test
    fun `load revalidates persisted snapshot before returning it`() = runTest {
        val store = RecordingStore(snapshot = ConfigSnapshot("tampered".encodeToByteArray()))
        val client = RemoteConfigClient(
            source = ConfigSource { error("unused") },
            store = store,
            decoder = ConfigDecoder(ByteArray::decodeToString),
            validator = ConfigValidator { _, _ -> error("invalid signature") },
        )

        val result = client.load()

        assertIs<RemoteConfigException.ValidationFailed>(result.exceptionOrNull())
    }

    @Test
    fun `decode failure does not invoke validator or store`() = runTest {
        var validated = false
        val store = RecordingStore()
        val client = RemoteConfigClient(
            source = ConfigSource { ConfigSnapshot(byteArrayOf(1)) },
            store = store,
            decoder = ConfigDecoder<String> { error("malformed") },
            validator = ConfigValidator { _, _ -> validated = true },
        )

        val result = client.refresh("device")

        assertIs<RemoteConfigException.DecodeFailed>(result.exceptionOrNull())
        assertTrue(!validated)
        assertNull(store.snapshot)
    }

    @Test
    fun `file store round trips complete snapshot`() = runTest {
        val directory = Files.createTempDirectory("remote-config-test")
        val store = FileConfigStore(directory)
        val snapshot = ConfigSnapshot(
            content = "config".encodeToByteArray(),
            signature = "signature".encodeToByteArray(),
            revision = "42",
        )

        store.save(snapshot)

        assertEquals(snapshot, store.load())
    }

    private class RecordingStore(
        private val events: MutableList<String> = mutableListOf(),
        var snapshot: ConfigSnapshot? = null,
    ) : ConfigStore {
        override suspend fun load(): ConfigSnapshot? = snapshot

        override suspend fun save(snapshot: ConfigSnapshot) {
            events += "save"
            this.snapshot = snapshot
        }
    }
}
