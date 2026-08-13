package com.xingzhi.remoteconfig

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fetches, decodes, validates, and atomically commits remote configuration snapshots.
 *
 * Invalid or partially fetched revisions never replace the last known-good local snapshot.
 * Callers observe a new configuration only after the complete transaction succeeds.
 */
class RemoteConfigClient<T>(
    private val source: ConfigSource,
    private val store: ConfigStore,
    private val decoder: ConfigDecoder<T>,
    private val validator: ConfigValidator<T> = ConfigValidator { _, _ -> },
) {
    private val refreshMutex = Mutex()

    /** Loads the last known-good snapshot from local storage. */
    suspend fun load(): Result<StoredConfig<T>?> = runCatching {
        store.load()?.let { snapshot ->
            val decoded = decode(snapshot)
            validate(snapshot, decoded)
            StoredConfig(value = decoded, snapshot = snapshot)
        }
    }

    /**
     * Refreshes [key] as one transaction: fetch → decode → validate → atomic commit.
     *
     * Concurrent callers are serialized so an older finishing request cannot overwrite a newer
     * committed revision.
     */
    suspend fun refresh(key: String): Result<StoredConfig<T>> = refreshMutex.withLock {
        runCatching {
            val snapshot = source.fetch(key)
            val decoded = decode(snapshot)
            validate(snapshot, decoded)
            try {
                store.save(snapshot)
            } catch (error: Throwable) {
                throw RemoteConfigException.StoreFailed(error)
            }
            StoredConfig(decoded, snapshot)
        }
    }

    private fun decode(snapshot: ConfigSnapshot): T = try {
        decoder.decode(snapshot.content)
    } catch (error: Throwable) {
        throw RemoteConfigException.DecodeFailed(error)
    }

    private suspend fun validate(snapshot: ConfigSnapshot, decoded: T) {
        try {
            validator.validate(snapshot, decoded)
        } catch (error: Throwable) {
            throw RemoteConfigException.ValidationFailed(error)
        }
    }
}
