package com.xingzhi.remoteconfig

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** HTTP adapter for conventional content and detached-signature resources. */
class HttpConfigSource(
    private val contentUri: (key: String) -> URI,
    private val signatureUri: ((key: String) -> URI)? = null,
    private val connectTimeout: Duration = 10.seconds,
    private val readTimeout: Duration = 30.seconds,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ConfigSource {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive." }
    }

    override suspend fun fetch(key: String): ConfigSnapshot = withContext(dispatcher) {
        ConfigSnapshot(
            content = download(contentUri(key)),
            signature = signatureUri?.let { download(it(key)) },
        )
    }

    private fun download(uri: URI): ByteArray {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeout.inWholeMilliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        connection.readTimeout = readTimeout.inWholeMilliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        connection.instanceFollowRedirects = true
        connection.requestMethod = "GET"
        return try {
            val code = connection.responseCode
            check(code in 200..299) { "GET $uri failed with HTTP $code." }
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0 || declaredLength <= maxResponseBytes) {
                "GET $uri response is too large: $declaredLength bytes."
            }
            connection.inputStream.use { it.readAtMost(maxResponseBytes) }
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readAtMost(limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= limit) { "HTTP response exceeds $limit bytes." }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Int = 4 * 1024 * 1024
    }
}
