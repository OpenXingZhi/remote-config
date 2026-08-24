package com.xingzhi.remoteconfig

import java.net.URI
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpConfigSourceTest {
    @Test
    fun `follows a cross-path 302 and returns the YAML body`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/final.yml")
                    .setBody("<a href=\"/final.yml\">Found</a>."),
            )
            server.enqueue(MockResponse().setBody("revision: 1\n"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(302)
                    .addHeader("Location", "/final.yml.asc")
                    .setBody("<a href=\"/final.yml.asc\">Found</a>."),
            )
            server.enqueue(MockResponse().setBody("-----BEGIN PGP SIGNATURE-----\n"))

            val source = HttpConfigSource(
                contentUri = { URI(server.url("$it.yml").toString()) },
                signatureUri = { URI(server.url("$it.yml.asc").toString()) },
            )

            val snapshot = source.fetch("28C2A3132F230D61")

            assertEquals("revision: 1\n", snapshot.content.decodeToString())
            assertEquals(
                "-----BEGIN PGP SIGNATURE-----\n",
                snapshot.signature?.decodeToString(),
            )
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `HTTP 404 is NotFound without putting the URL in the exception message`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

            val source = HttpConfigSource(
                contentUri = { URI(server.url("$it.yml").toString()) },
                signatureUri = { URI(server.url("$it.yml.asc").toString()) },
            )

            val error = assertFailsWith<RemoteConfigException.NotFound> {
                source.fetch("28C2A3132F230D61")
            }

            assertFalse(error.message!!.contains("http", ignoreCase = true))
            assertFalse(error.message!!.contains("404"))
            assertFalse(error.message!!.contains("28C2A3132F230D61"))
        }
    }

    @Test
    fun `HTTP 404 on the signature file is SignatureNotFound`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("revision: 1\n"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

            val source = HttpConfigSource(
                contentUri = { URI(server.url("$it.yml").toString()) },
                signatureUri = { URI(server.url("$it.yml.asc").toString()) },
            )

            val error = assertFailsWith<RemoteConfigException.SignatureNotFound> {
                source.fetch("28C2A3132F230D61")
            }

            assertFalse(error.message!!.contains("http", ignoreCase = true))
            assertFalse(error.message!!.contains("404"))
            assertFalse(error.message!!.contains("yml.asc"))
        }
    }

    @Test
    fun `non-404 HTTP failures keep the status and are not NotFound`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))

            val source = HttpConfigSource(
                contentUri = { URI(server.url("$it.yml").toString()) },
                signatureUri = { URI(server.url("$it.yml.asc").toString()) },
            )

            val error = assertFailsWith<IllegalStateException> {
                source.fetch("device")
            }

            assertTrue(error.message!!.contains("503"))
        }
    }
}

class OkHttpFetcherTest {
    @Test
    fun `returns the response body`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("-----BEGIN PGP PUBLIC KEY BLOCK-----\n"))
            val fetcher = OkHttpFetcher()

            val bytes = fetcher.get(URI(server.url("/chuxubank.gpg").toString()))

            assertEquals("-----BEGIN PGP PUBLIC KEY BLOCK-----\n", bytes.decodeToString())
        }
    }

    @Test
    fun `HTTP 404 is a transport failure not NotFound`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))
            val fetcher = OkHttpFetcher()

            val error = assertFailsWith<IllegalStateException> {
                fetcher.get(URI(server.url("/missing.gpg").toString()))
            }

            assertTrue(error.message!!.contains("404"))
        }
    }
}
