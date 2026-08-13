package com.xingzhi.remoteconfig

/** Immutable bytes fetched and committed as one configuration revision. */
data class ConfigSnapshot(
    val content: ByteArray,
    val signature: ByteArray? = null,
    val revision: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is ConfigSnapshot &&
            content.contentEquals(other.content) &&
            signaturesEqual(signature, other.signature) &&
            revision == other.revision

    override fun hashCode(): Int {
        var result = content.contentHashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + (revision?.hashCode() ?: 0)
        return result
    }

    private fun signaturesEqual(first: ByteArray?, second: ByteArray?): Boolean = when {
        first === second -> true
        first == null || second == null -> false
        else -> first.contentEquals(second)
    }
}

/** Remote adapter. Fetching does not mutate local state. */
fun interface ConfigSource {
    suspend fun fetch(key: String): ConfigSnapshot
}

/** Durable adapter. [save] must atomically replace the previous snapshot. */
interface ConfigStore {
    suspend fun load(): ConfigSnapshot?
    suspend fun save(snapshot: ConfigSnapshot)
}

/** Application adapter for parsing its own configuration schema. */
fun interface ConfigDecoder<T> {
    fun decode(content: ByteArray): T
}

/** Optional trust/business policy run before a snapshot becomes visible. */
fun interface ConfigValidator<T> {
    suspend fun validate(snapshot: ConfigSnapshot, decoded: T)
}

/** Policy comparing an authenticated candidate with the last authenticated local value. */
fun interface ConfigRevisionPolicy<T> {
    fun validate(current: StoredConfig<T>?, candidate: StoredConfig<T>)
}

/** Rejects lower revisions and same-revision content changes while allowing idempotent refreshes. */
class MonotonicLongRevisionPolicy<T>(
    private val revisionOf: (T) -> Long,
) : ConfigRevisionPolicy<T> {
    override fun validate(current: StoredConfig<T>?, candidate: StoredConfig<T>) {
        if (current == null) return
        val currentRevision = revisionOf(current.value)
        val candidateRevision = revisionOf(candidate.value)
        require(candidateRevision >= currentRevision) {
            "Configuration revision $candidateRevision is older than $currentRevision."
        }
        require(
            candidateRevision != currentRevision ||
                candidate.snapshot.content.contentEquals(current.snapshot.content)
        ) { "Configuration content changed without advancing revision $candidateRevision." }
    }
}

/** A locally committed, parsed configuration. */
data class StoredConfig<T>(
    val value: T,
    val snapshot: ConfigSnapshot,
)

sealed class RemoteConfigException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class DecodeFailed(cause: Throwable) :
        RemoteConfigException("Remote configuration could not be decoded.", cause)

    class ValidationFailed(cause: Throwable) :
        RemoteConfigException("Remote configuration validation failed.", cause)

    class StoreFailed(cause: Throwable) :
        RemoteConfigException("Remote configuration could not be committed.", cause)

    class RollbackRejected(cause: Throwable) :
        RemoteConfigException("Remote configuration revision was rejected.", cause)
}
