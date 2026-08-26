package com.xingzhi.remoteconfig

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** HTTP adapter for conventional content and detached-signature resources. */
class HttpConfigSource(
    private val contentUri: (key: String) -> URI,
    private val signatureUri: ((key: String) -> URI)? = null,
    private val connectTimeout: Duration = 10.seconds,
    private val readTimeout: Duration = 30.seconds,
    private val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val callFactory: Call.Factory = defaultCallFactory(connectTimeout, readTimeout),
) : ConfigSource {
    init {
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive." }
    }

    override suspend fun fetch(key: String): ConfigSnapshot = try {
        withContext(dispatcher) {
            ConfigSnapshot(
                content = download(contentUri(key)) { RemoteConfigException.NotFound() },
                signature = signatureUri?.let {
                    download(it(key)) { RemoteConfigException.SignatureNotFound() }
                },
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: RemoteConfigException) {
        throw error
    } catch (error: Throwable) {
        throw RemoteConfigException.FetchFailed(error)
    }

    private suspend fun download(uri: URI, onMissing: () -> Throwable): ByteArray =
        awaitBytes(callFactory, uri, maxResponseBytes, onMissing)

    companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES: Int = 4 * 1024 * 1024
        const val DEFAULT_PUBLIC_KEY_BYTES: Int = 64 * 1024

        fun defaultCallFactory(
            connectTimeout: Duration = 10.seconds,
            readTimeout: Duration = 30.seconds,
        ): Call.Factory = OkHttpClient.Builder()
            .connectTimeout(connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}

/** Bounded GET for small documents such as a pinned OpenPGP certificate. */
class OkHttpFetcher(
    private val callFactory: Call.Factory = HttpConfigSource.defaultCallFactory(),
    private val maxBytes: Int = HttpConfigSource.DEFAULT_PUBLIC_KEY_BYTES,
) {
    fun get(uri: URI): ByteArray = getBytes(callFactory, uri, maxBytes)
}

internal fun getBytes(
    callFactory: Call.Factory,
    uri: URI,
    maxBytes: Int,
    onMissing: (() -> Throwable)? = null,
): ByteArray {
    val response = newGetCall(callFactory, uri).execute()
    return response.use { readBoundedBody(it, uri, maxBytes, onMissing) }
}

internal suspend fun awaitBytes(
    callFactory: Call.Factory,
    uri: URI,
    maxBytes: Int,
    onMissing: (() -> Throwable)? = null,
): ByteArray = suspendCancellableCoroutine { continuation ->
    val call = newGetCall(callFactory, uri)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use { readBoundedBody(it, uri, maxBytes, onMissing) } }
            if (continuation.isCancelled) return
            result.fold(
                onSuccess = { continuation.resume(it) },
                onFailure = { continuation.resumeWithException(it) },
            )
        }
    })
}

private fun newGetCall(callFactory: Call.Factory, uri: URI): Call =
    callFactory.newCall(Request.Builder().url(uri.toURL()).get().build())

private fun readBoundedBody(
    response: Response,
    uri: URI,
    maxBytes: Int,
    onMissing: (() -> Throwable)?,
): ByteArray {
    if (response.code == 404 && onMissing != null) {
        throw onMissing()
    }
    check(response.isSuccessful) { "GET $uri failed with HTTP ${response.code}." }
    val body = checkNotNull(response.body)
    val declared = body.contentLength()
    require(declared < 0 || declared <= maxBytes) {
        "GET $uri response is too large: $declared bytes."
    }
    val bytes = body.byteStream().use { stream -> stream.readAtMost(maxBytes) }
    check(bytes.size <= maxBytes) { "GET $uri response exceeds $maxBytes bytes." }
    return bytes
}

private fun java.io.InputStream.readAtMost(limit: Int): ByteArray {
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
