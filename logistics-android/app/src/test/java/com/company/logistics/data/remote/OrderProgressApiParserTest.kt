package com.company.logistics.data.remote

import com.company.logistics.model.OrderProgress
import com.company.logistics.model.displayProgressPercent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批次 2B 项 A：订单维度进度解析。
 *
 * 契约来源：后端 `GET /api/v1/workshop/order-progress`（main.py:4119）与
 * 订单详情 `progress.orderProgressPercent`（main.py:4689）。
 */
class OrderProgressApiParserTest {

    @Test
    fun parsesContractPayloadWithOrderProgressPercentAndTaskCounts() {
        val json = """
            {
              "items": [
                {"orderNo":"PO-2026-001","totalTasks":4,"completedTasks":1,
                 "waitingMaterialTasks":1,"inProgressTasks":2,"orderProgressPercent":45},
                {"orderNo":"PO-2026-002","totalTasks":2,"completedTasks":2,
                 "waitingMaterialTasks":0,"inProgressTasks":0,"orderProgressPercent":100}
              ],
              "page":1,"pageSize":50,"workshop":"装配一车间","scope":"WORKSHOP",
              "serverTime":"2026-10-22T09:30:00+08:00","generatedAt":"2026-10-22T09:30:00+08:00"
            }
        """.trimIndent()

        val page = ApiParser.parseOrderProgressPage(json)

        assertEquals(2, page.items.size)
        assertEquals("PO-2026-001", page.items[0].orderNo)
        assertEquals(45, page.items[0].orderProgressPercent)
        assertEquals(4, page.items[0].totalTasks)
        assertEquals(1, page.items[0].completedTasks)
        assertEquals(1, page.items[0].waitingMaterialTasks)
        assertEquals(2, page.items[0].inProgressTasks)
        assertEquals(100, page.items[1].orderProgressPercent)
        assertEquals(1, page.page)
        assertEquals(50, page.pageSize)
        assertEquals("装配一车间", page.workshop)
        assertEquals("WORKSHOP", page.scope)
        assertEquals("2026-10-22T09:30:00+08:00", page.serverTime)
    }

    @Test
    fun missingItemsArrayYieldsEmptyPageInsteadOfThrowing() {
        val page = ApiParser.parseOrderProgressPage("""{"page":2,"pageSize":10}""")

        assertTrue(page.items.isEmpty())
        assertEquals(2, page.page)
        assertEquals(10, page.pageSize)
        assertNull(page.workshop)
        assertNull(page.scope)
    }

    @Test
    fun emptyJsonObjectAndNullItemsBothParseToEmptyPage() {
        assertEquals(0, ApiParser.parseOrderProgressPage("{}").items.size)
        assertEquals(0, ApiParser.parseOrderProgressPage("""{"items":null}""").items.size)
        assertEquals(0, ApiParser.parseOrderProgressPage("""{"items":[]}""").items.size)
    }

    @Test
    fun nullOrMissingOrderProgressPercentStaysNullSoUiNeverShowsZeroPercent() {
        val json = """
            {"items":[
              {"orderNo":"PO-A","totalTasks":3,"completedTasks":0,"orderProgressPercent":null},
              {"orderNo":"PO-B","totalTasks":3,"completedTasks":0},
              {"orderNo":"PO-C","totalTasks":0,"completedTasks":0,"orderProgressPercent":0}
            ]}
        """.trimIndent()

        val items = ApiParser.parseOrderProgressPage(json).items

        // null / 缺字段 → null（UI 显示「—」），而不是被 optInt 兜成 0
        assertNull(items[0].orderProgressPercent)
        assertNull(items[1].orderProgressPercent)
        // 服务端真的给了 0 才显示 0%
        assertEquals(0, items[2].orderProgressPercent)

        assertEquals("—", items[0].displayProgressPercent)
        assertEquals("—", items[1].displayProgressPercent)
        assertEquals("0%", items[2].displayProgressPercent)
        assertEquals("45%", OrderProgress("PO-D", 45).displayProgressPercent)
    }

    @Test
    fun missingOptionalFieldsFallBackToZeroWithoutCrash() {
        val page = ApiParser.parseOrderProgressPage("""{"items":[{"orderNo":"PO-MIN"}]}""")

        val item = page.items.single()
        assertEquals("PO-MIN", item.orderNo)
        assertNull(item.orderProgressPercent)
        assertEquals(0, item.totalTasks)
        assertEquals(0, item.completedTasks)
        assertEquals(0, item.waitingMaterialTasks)
        assertEquals(0, item.inProgressTasks)
    }

    @Test
    fun nullArrayElementIsSkippedInsteadOfCrashing() {
        val json = """{"items":[null,{"orderNo":"PO-OK","orderProgressPercent":12}]}"""

        val items = ApiParser.parseOrderProgressPage(json).items

        assertEquals(1, items.size)
        assertEquals("PO-OK", items.single().orderNo)
        assertEquals(12, items.single().orderProgressPercent)
    }

    @Test
    fun snakeCaseFallbackFieldsAreAccepted() {
        val json = """
            {"items":[{"order_no":"PO-SNAKE","order_progress_percent":66,
                       "total_tasks":5,"completed_tasks":3,"waiting_tasks":1,"in_progress_tasks":1}],
             "workshop_id":"WS-2","server_time":"2026-10-22T10:00:00+08:00"}
        """.trimIndent()

        val page = ApiParser.parseOrderProgressPage(json)
        val item = page.items.single()

        assertEquals("PO-SNAKE", item.orderNo)
        assertEquals(66, item.orderProgressPercent)
        assertEquals(5, item.totalTasks)
        assertEquals(3, item.completedTasks)
        assertEquals(1, item.waitingMaterialTasks)
        assertEquals(1, item.inProgressTasks)
        assertEquals("WS-2", page.workshop)
        assertEquals("2026-10-22T10:00:00+08:00", page.serverTime)
    }

    @Test
    fun orderDetailExposesThisOrdersProgressFromProgressSection() {
        val json = """
            {
              "orderId":"ord-1","orderNo":"PO-2026-001","productName":"减速机","orderStatus":"IN_PROGRESS",
              "materials":[],"assemblyTasks":[],"timeline":[],
              "laborSummary":{"assemblyLaborMinutes":10,"temporaryTransferLaborMinutes":0,"totalLaborMinutes":10},
              "progress":{"totalTasks":4,"completedTasks":1,"waitingMaterialTasks":1,
                          "inProgressTasks":2,"orderProgressPercent":45},
              "page":1,"pageSize":20,"total":0
            }
        """.trimIndent()

        val detail = ApiParser.parseOrderDetail(json)

        val progress = detail.orderProgress
        assertNotNull(progress)
        assertEquals("PO-2026-001", progress!!.orderNo)
        assertEquals(45, progress.orderProgressPercent)
        assertEquals(4, progress.totalTasks)
        assertEquals(1, progress.completedTasks)
    }

    @Test
    fun orderDetailWithoutProgressSectionKeepsNullInsteadOfZero() {
        val json = """
            {"orderId":"ord-2","orderNo":"PO-2026-002","materials":[],"assemblyTasks":[],"timeline":[],
             "laborSummary":{},"page":1,"pageSize":20,"total":0}
        """.trimIndent()

        assertNull(ApiParser.parseOrderDetail(json).orderProgress)
    }

    @Test
    fun orderDetailProgressWithoutOwnOrderNoInheritsHeaderOrderNo() {
        val json = """
            {"orderId":"ord-3","orderNo":"PO-2026-003","materials":[],"assemblyTasks":[],"timeline":[],
             "laborSummary":{},"progress":{"orderProgressPercent":30},"page":1,"pageSize":20,"total":0}
        """.trimIndent()

        val progress = ApiParser.parseOrderDetail(json).orderProgress

        assertEquals("PO-2026-003", progress?.orderNo)
        assertEquals(30, progress?.orderProgressPercent)
    }
}
