package com.company.logistics.data.remote

import java.io.IOException
import java.net.UnknownServiceException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 2B 项 B：明文地址被系统拦截时，用户要看到「正式版要求 HTTPS，请联系管理员」，
 * 而不是一句裸 IOException。
 */
class CleartextTrafficGuardTest {

    private val okHttpMessage =
        "CLEARTEXT communication to 192.168.1.10 not permitted by network security policy"

    @Test
    fun detectsPlatformCleartextBlockMessage() {
        assertTrue(CleartextTrafficGuard.isBlocked(okHttpMessage))
        // 大小写 / 措辞差异（不同 OkHttp 版本、HttpURLConnection 包装）都要认得
        assertTrue(CleartextTrafficGuard.isBlocked("cleartext traffic to api.local was BLOCKED"))
        assertTrue(CleartextTrafficGuard.isBlocked("Cleartext HTTP traffic not permitted"))
        assertFalse(CleartextTrafficGuard.isBlocked("timeout"))
        assertFalse(CleartextTrafficGuard.isBlocked("Unable to resolve host \"api.local\""))
        assertFalse(CleartextTrafficGuard.isBlocked(null))
    }

    @Test
    fun blockedIOExceptionBecomesReadableApiException() {
        val thrown = runCatching { CleartextTrafficGuard.rethrow(UnknownServiceException(okHttpMessage)) }
            .exceptionOrNull()

        assertNotNull(thrown)
        val error = thrown as ApiException
        assertEquals(CleartextTrafficGuard.CODE, error.code)
        assertEquals(CleartextTrafficGuard.MESSAGE, error.message)
        assertTrue("提示必须给出 HTTPS 处置建议", error.message.contains("HTTPS"))
        assertTrue("提示必须指向管理员", error.message.contains("管理员"))
        assertFalse("明文拦截属于配置问题，不是可重试的网络抖动", error.retryable)
        // 不能被 safeMessage 的兜底文案吃掉
        assertEquals(CleartextTrafficGuard.MESSAGE, error.safeMessage("订单进度加载失败，请稍后重试"))
    }

    @Test
    fun unrelatedIOExceptionKeepsOriginalTypeAndMessage() {
        val thrown = runCatching { CleartextTrafficGuard.rethrow(IOException("Connection reset")) }
            .exceptionOrNull()

        assertEquals(IOException::class.java, thrown!!.javaClass)
        assertEquals("Connection reset", thrown.message)
    }

    @Test
    fun describeOnlyFillsInForCleartextBlocks() {
        assertEquals(CleartextTrafficGuard.MESSAGE, CleartextTrafficGuard.describe(okHttpMessage))
        assertNull(CleartextTrafficGuard.describe("Connection reset"))
        assertNull(CleartextTrafficGuard.describe(null))
    }
}
