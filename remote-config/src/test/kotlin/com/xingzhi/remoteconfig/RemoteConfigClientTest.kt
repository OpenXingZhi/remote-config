package com.xingzhi.remoteconfig

import java.nio.file.Files
import kotlinx.coroutines.CancellationException
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
    fun `typed remote config exception from validator is preserved`() = runTest {
        val expected = RemoteConfigException.SignatureNotFound()
        val client = RemoteConfigClient(
            source = ConfigSource { ConfigSnapshot("unsigned".encodeToByteArray()) },
            store = RecordingStore(),
            decoder = ConfigDecoder(ByteArray::decodeToString),
            validator = ConfigValidator { _, _ -> throw expected },
        )

        assertEquals(expected, client.refresh("device").exceptionOrNull())
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
    fun `rejects older and same revision with changed content`() = runTest {
        data class Versioned(val revision: Long, val content: String)
        fun snapshot(revision: Long, content: String) =
            ConfigSnapshot("$revision:$content".encodeToByteArray())
        val old = snapshot(2, "accepted")
        val store = RecordingStore(snapshot = old)
        var candidate = snapshot(1, "older")
        val client = RemoteConfigClient(
            source = ConfigSource { candidate },
            store = store,
            decoder = ConfigDecoder { bytes ->
                bytes.decodeToString().split(':', limit = 2).let { Versioned(it[0].toLong(), it[1]) }
            },
            revisionPolicy = MonotonicLongRevisionPolicy(Versioned::revision),
        )

        assertIs<RemoteConfigException.RollbackRejected>(
            client.refresh("device").exceptionOrNull()
        )
        candidate = snapshot(2, "changed-without-version")
        assertIs<RemoteConfigException.RollbackRejected>(
            client.refresh("device").exceptionOrNull()
        )
        assertEquals(old, store.snapshot)
    }

    @Test
    fun `allows idempotent refresh and a newer revision`() = runTest {
        data class Versioned(val revision: Long)
        val old = ConfigSnapshot("2".encodeToByteArray())
        val store = RecordingStore(snapshot = old)
        var candidate = old
        val client = RemoteConfigClient(
            source = ConfigSource { candidate },
            store = store,
            decoder = ConfigDecoder { Versioned(it.decodeToString().toLong()) },
            revisionPolicy = MonotonicLongRevisionPolicy(Versioned::revision),
        )

        assertTrue(client.refresh("device").isSuccess)
        candidate = ConfigSnapshot("3".encodeToByteArray())
        assertTrue(client.refresh("device").isSuccess)
        assertEquals(candidate, store.snapshot)
    }

    @Test
    fun `refresh replaces an undecodable local snapshot with a valid remote`() = runTest {
        data class Versioned(val revision: Long, val body: String)
        val stale = ConfigSnapshot("legacy".encodeToByteArray())
        val remote = ConfigSnapshot("2:current".encodeToByteArray())
        val store = RecordingStore(snapshot = stale)
        val client = RemoteConfigClient(
            source = ConfigSource { remote },
            store = store,
            decoder = ConfigDecoder { bytes ->
                val text = bytes.decodeToString()
                val parts = text.split(':', limit = 2)
                require(parts.size == 2) { "legacy schema" }
                Versioned(parts[0].toLong(), parts[1])
            },
            revisionPolicy = MonotonicLongRevisionPolicy(Versioned::revision),
        )

        val result = client.refresh("device")

        assertEquals(Versioned(2, "current"), result.getOrThrow().value)
        assertEquals(remote, store.snapshot)
        assertEquals(Versioned(2, "current"), client.load().getOrThrow()?.value)
    }

    @Test
    fun `refresh replaces a locally invalid snapshot with a valid remote`() = runTest {
        val stale = ConfigSnapshot("stale".encodeToByteArray(), "old-sig".encodeToByteArray())
        val remote = ConfigSnapshot("fresh".encodeToByteArray(), "new-sig".encodeToByteArray())
        val store = RecordingStore(snapshot = stale)
        val client = RemoteConfigClient(
            source = ConfigSource { remote },
            store = store,
            decoder = ConfigDecoder(ByteArray::decodeToString),
            validator = ConfigValidator { snapshot, _ ->
                require(snapshot.signature?.decodeToString() == "new-sig") { "bad signature" }
            },
            revisionPolicy = MonotonicLongRevisionPolicy { 1L },
        )

        assertEquals("fresh", client.refresh("device").getOrThrow().value)
        assertEquals(remote, store.snapshot)
    }

    @Test
    fun `load still fails when the persisted snapshot cannot be decoded`() = runTest {
        val store = RecordingStore(snapshot = ConfigSnapshot("legacy".encodeToByteArray()))
        val client = RemoteConfigClient(
            source = ConfigSource { error("unused") },
            store = store,
            decoder = ConfigDecoder<String> { error("legacy schema") },
        )

        assertIs<RemoteConfigException.DecodeFailed>(client.load().exceptionOrNull())
        assertEquals("legacy", store.snapshot?.content?.decodeToString())
    }

    @Test
    fun `imports provided snapshot without fetching`() = runTest {
        val events = mutableListOf<String>()
        val snapshot = ConfigSnapshot("imported".encodeToByteArray(), "sig".encodeToByteArray())
        val store = RecordingStore(events)
        val client = RemoteConfigClient(
            source = ConfigSource { events += "fetch"; error("unused") },
            store = store,
            decoder = ConfigDecoder { events += "decode"; it.decodeToString() },
            validator = ConfigValidator { _, value -> events += "validate:$value" },
        )

        val result = client.import(snapshot)

        assertEquals("imported", result.getOrThrow().value)
        assertEquals(listOf("decode", "validate:imported", "save"), events)
        assertEquals(snapshot, store.snapshot)
    }

    @Test
    fun `import rejects older revision and keeps last known good snapshot`() = runTest {
        data class Versioned(val revision: Long)
        val accepted = ConfigSnapshot("2".encodeToByteArray())
        val store = RecordingStore(snapshot = accepted)
        val client = RemoteConfigClient(
            source = ConfigSource { error("unused") },
            store = store,
            decoder = ConfigDecoder { Versioned(it.decodeToString().toLong()) },
            revisionPolicy = MonotonicLongRevisionPolicy(Versioned::revision),
        )

        val result = client.import(ConfigSnapshot("1".encodeToByteArray()))

        assertIs<RemoteConfigException.RollbackRejected>(result.exceptionOrNull())
        assertEquals(accepted, store.snapshot)
    }

    @Test
    fun `reload returns null when the store is empty`() = runTest {
        val events = mutableListOf<String>()
        val client = RemoteConfigClient(
            source = ConfigSource { events += "fetch"; error("unused") },
            store = RecordingStore(events),
            decoder = ConfigDecoder { events += "decode"; it.decodeToString() },
        )

        val result = client.reload()

        assertNull(result.getOrThrow())
        assertEquals(emptyList(), events)
    }

    @Test
    fun `reload revalidates the stored snapshot before returning it`() = runTest {
        val events = mutableListOf<String>()
        val snapshot = ConfigSnapshot("local".encodeToByteArray())
        val store = RecordingStore(events, snapshot)
        val client = RemoteConfigClient(
            source = ConfigSource { events += "fetch"; error("unused") },
            store = store,
            decoder = ConfigDecoder { events += "decode"; it.decodeToString() },
            validator = ConfigValidator { _, value -> events += "validate:$value" },
        )

        val result = client.reload()

        assertEquals("local", result.getOrThrow()?.value)
        assertEquals(listOf("decode", "validate:local", "save"), events)
        assertEquals(snapshot, store.snapshot)
    }

    @Test
    fun `reload validation failure keeps last known good snapshot`() = runTest {
        val accepted = ConfigSnapshot("accepted".encodeToByteArray())
        val store = RecordingStore(snapshot = accepted)
        val client = RemoteConfigClient(
            source = ConfigSource { error("unused") },
            store = store,
            decoder = ConfigDecoder(ByteArray::decodeToString),
            validator = ConfigValidator { _, _ -> error("invalid signature") },
        )

        val result = client.reload()

        assertIs<RemoteConfigException.ValidationFailed>(result.exceptionOrNull())
        assertEquals(accepted, store.snapshot)
    }

    @Test
    fun `does not convert cancellation into failure`() = runTest {
        val client = RemoteConfigClient(
            source = ConfigSource { throw CancellationException("cancel") },
            store = RecordingStore(),
            decoder = ConfigDecoder(ByteArray::decodeToString),
        )

        assertFailsWith<CancellationException> { client.refresh("device") }
    }

    @Test
    fun `wraps source failure as FetchFailed and preserves cause`() = runTest {
        val cause = IllegalStateException("transport detail")
        val client = RemoteConfigClient(
            source = ConfigSource { throw cause },
            store = RecordingStore(),
            decoder = ConfigDecoder(ByteArray::decodeToString),
        )

        val error = assertIs<RemoteConfigException.FetchFailed>(
            client.refresh("device").exceptionOrNull(),
        )

        assertEquals(cause, error.cause)
    }

    @Test
    fun `wraps store load failure as StoreFailed and preserves cause`() = runTest {
        val cause = IllegalStateException("disk detail")
        val client = RemoteConfigClient(
            source = ConfigSource { error("unused") },
            store = object : ConfigStore {
                override suspend fun load(): ConfigSnapshot? = throw cause
                override suspend fun save(snapshot: ConfigSnapshot) = Unit
            },
            decoder = ConfigDecoder(ByteArray::decodeToString),
        )

        val error = assertIs<RemoteConfigException.StoreFailed>(
            client.load().exceptionOrNull(),
        )

        assertEquals(cause, error.cause)
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
