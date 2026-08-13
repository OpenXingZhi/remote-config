package com.xingzhi.remoteconfig

import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HTTP adapter for conventional `<key>` and `<key><signatureSuffix>` resources.
 *
 * URI construction remains caller-controlled, so this adapter works with GitHub, Gitee, object
 * storage, and owned HTTP services without embedding one vendor in the module.
 */
class HttpConfigSource(
    private val contentUri: (key: String) -> URI,
    private val signatureUri: ((key: String) -> URI)? = null,
    private val connectTimeout: Duration = 10.seconds,
    private val readTimeout: Duration = 30.seconds,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConfigSource {
    override suspend fun fetch(key: String): ConfigSnapshot = withContext(dispatcher) {
        ConfigSnapshot(
            content = download(contentUri(key)),
            signature = signatureUri?.let { download(it(key)) },
        )
    }

    private fun download(uri: URI): ByteArray {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeout.inWholeMilliseconds.toInt()
        connection.readTimeout = readTimeout.inWholeMilliseconds.toInt()
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        return try {
            val code = connection.responseCode
            check(code in 200..299) { "GET $uri failed with HTTP $code." }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
