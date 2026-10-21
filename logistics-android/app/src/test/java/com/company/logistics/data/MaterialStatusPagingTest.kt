package com.company.logistics.data

import com.company.logistics.data.remote.ApiConfig
import com.company.logistics.data.remote.MaterialFlowApi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 订单物料状态分页（0.5.48 起后端支持）。
 *
 * 注意：App 当前仍按全量请求 —— 批量出库、缺料数都依赖整份清单，翻页要连 UI 一起改。
 * 这里锁住两件事，保证以后开分页时不会把老请求改坏：
 * 1. 不传 pageSize 时请求体里不出现分页键（老行为不变）；
 * 2. 传了分页参数时，齐套率必须用服务端全量汇总，而不是当前页求和。
 */
class MaterialStatusPagingTest {

    @Test
    fun defaultRequestHasNoPagingKeys() = runBlocking {
        val body = captureBody { api -> api.orderMaterialStatus("PRODUCTION_ORDER", "OM-1") }
        assertFalse("默认请求不应带 page", body.has("page"))
        assertFalse("默认请求不应带 pageSize", body.has("pageSize"))
        assertEquals("OM-1", body.getString("documentNo"))
    }

    @Test
    fun explicitPagingSendsPageAndSize() = runBlocking {
        val body = captureBody { api -> api.orderMaterialStatus("PRODUCTION_ORDER", "OM-2", page = 2, pageSize = 200) }
        assertEquals(2, body.getInt("page"))
        assertEquals(200, body.getInt("pageSize"))
    }

    @Test
    fun fulfillmentRateUsesServerSummaryWhenPaged() {
        val json = """
            {"documentNo":"OM-3","documentType":"PRODUCTION_ORDER",
             "items":[{"materialId":"m1","materialCode":"M-1","materialName":"甲",
                       "requiredQuantity":10,"arrivedQuantity":5,"inStockQuantity":5,"statusCode":"OUT_OF_STOCK"}],
             "materialSummary":{"totalRequiredQuantity":100,"totalArrivedQuantity":60,
                                "totalInStockQuantity":70,"totalShortageQuantity":30,"items":[]},
             "total":400,"page":1,"pageSize":1,"hasMore":true}
        """.trimIndent()
        val status = com.company.logistics.data.remote.ApiParser.parseOrderMaterialStatus(json)
        assertEquals(400, status.total)
        assertTrue(status.hasMore)
        // 70/100 来自服务端全量汇总；若误用当前页求和会是 5/10
        assertEquals(0.7f, status.fulfillmentRate, 0.001f)
    }

    private suspend fun captureBody(call: suspend (MaterialFlowApi) -> Unit): JSONObject {
        val server = ServerSocket(0)
        var captured = ""
        val worker = Thread {
            server.accept().use { socket: Socket ->
                captured = readBody(socket)
                respond(socket)
            }
        }.apply { isDaemon = true }
        worker.start()
        val old = ApiConfig.baseUrl
        try {
            ApiConfig.baseUrl = "http://127.0.0.1:${server.localPort}"
            call(MaterialFlowApi().also { it.updateToken("token") })
        } finally {
            ApiConfig.baseUrl = old
            server.close()
        }
        worker.join(5_000)
        return JSONObject(captured)
    }

    private fun readBody(socket: Socket): String {
        val input = java.io.BufferedInputStream(socket.getInputStream())
        fun readLine(): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                if (b != '\r'.code) bytes.write(b)
            }
            return bytes.toString(Charsets.UTF_8.name())
        }
        readLine()
        var length = 0
        while (true) {
            val line = readLine()
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                length = line.substringAfter(':').trim().toIntOrNull() ?: 0
            }
        }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(body, offset, length - offset)
            if (read < 0) break
            offset += read
        }
        return String(body, 0, offset, Charsets.UTF_8)
    }

    private fun respond(socket: Socket) {
        val body = """{"documentNo":"OM-1","documentType":"PRODUCTION_ORDER","items":[],"total":0}"""
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.size}\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray()); write(payload); flush() }
    }
}
