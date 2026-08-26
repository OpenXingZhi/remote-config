package com.xingzhi.remoteconfig

import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class RemoteConfigExceptionTest {
    @Test
    fun `finds structured remote config exception in cause chain`() {
        val remoteError = RemoteConfigException.FetchFailed(IllegalStateException("network"))
        val wrapped = IllegalStateException("refresh failed", remoteError)

        assertSame(remoteError, wrapped.findRemoteConfigException())
    }

    @Test
    fun `returns null when cause chain has no remote config exception`() {
        assertNull(IllegalStateException("unrelated").findRemoteConfigException())
    }
}
