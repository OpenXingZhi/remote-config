package com.xingzhi.remoteconfig

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches, decodes, validates, checks revision ordering, and atomically commits snapshots.
 *
 * Invalid, partially fetched, or rolled-back revisions never replace the last known-good local
 * snapshot. Callers observe a new configuration only after the complete transaction succeeds.
 */
class RemoteConfigClient<T>(
    private val source: ConfigSource,
    private val store: ConfigStore,
    private val decoder: ConfigDecoder<T>,
    private val validator: ConfigValidator<T> = ConfigValidator { _, _ -> },
    private val revisionPolicy: ConfigRevisionPolicy<T>? = null,
) {
    private val refreshMutex = Mutex()

    /** Loads and revalidates the last known-good snapshot from local storage. */
    suspend fun load(): Result<StoredConfig<T>?> = resultOf {
        loadValidated()
    }

    /**
     * Commits [snapshot] as one transaction: decode → validate → revision check → save.
     *
     * Use this for a complete snapshot already in hand, such as a field import or a
     * single-file watcher payload. The remote source is not consulted.
     */
    suspend fun import(snapshot: ConfigSnapshot): Result<StoredConfig<T>> = refreshMutex.withLock {
        resultOf { commit(snapshot) }
    }

    /**
     * Revalidates the persisted snapshot and rewrites it only if the complete transaction succeeds.
     */
    suspend fun reload(): Result<StoredConfig<T>?> = refreshMutex.withLock {
        resultOf {
            val snapshot = store.load() ?: return@resultOf null
            commit(snapshot, checkRevisionAgainstStore = false)
        }
    }

    /**
     * Refreshes [key] as one transaction: fetch → decode → validate → revision check → commit.
     */
    suspend fun refresh(key: String): Result<StoredConfig<T>> = refreshMutex.withLock {
        resultOf { commit(source.fetch(key)) }
    }

    private suspend fun commit(
        snapshot: ConfigSnapshot,
        checkRevisionAgainstStore: Boolean = true,
    ): StoredConfig<T> {
        val candidate = StoredConfig(decode(snapshot), snapshot)
        validate(candidate.snapshot, candidate.value)

        revisionPolicy?.let { policy ->
            val current = if (checkRevisionAgainstStore) loadValidated() else null
            try {
                policy.validate(current, candidate)
            } catch (error: Throwable) {
                error.rethrowCancellation()
                throw RemoteConfigException.RollbackRejected(error)
            }
        }

        try {
            store.save(snapshot)
        } catch (error: Throwable) {
            error.rethrowCancellation()
            throw RemoteConfigException.StoreFailed(error)
        }
        return candidate
    }

    private suspend fun loadValidated(): StoredConfig<T>? = store.load()?.let { snapshot ->
        val decoded = decode(snapshot)
        validate(snapshot, decoded)
        StoredConfig(value = decoded, snapshot = snapshot)
    }

    private fun decode(snapshot: ConfigSnapshot): T = try {
        decoder.decode(snapshot.content)
    } catch (error: Throwable) {
        error.rethrowCancellation()
        throw RemoteConfigException.DecodeFailed(error)
    }

    private suspend fun validate(snapshot: ConfigSnapshot, decoded: T) {
        try {
            validator.validate(snapshot, decoded)
        } catch (error: Throwable) {
            error.rethrowCancellation()
            throw RemoteConfigException.ValidationFailed(error)
        }
    }

    private suspend inline fun <R> resultOf(crossinline block: suspend () -> R): Result<R> = try {
        Result.success(block())
    } catch (error: Throwable) {
        error.rethrowCancellation()
        Result.failure(error)
    }

    private fun Throwable.rethrowCancellation() {
        if (this is CancellationException) throw this
    }
}
