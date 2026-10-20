package com.company.logistics.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * 批次一 · 项 9：emoji 图标门禁（scripts/check_emoji_icons.py）的三处历史 FAIL
 * 改成矢量图标后，图标集合必须真的提供对应图形——否则门禁绿了、界面却空着。
 *
 * 覆盖：
 *  - [LogisticsIcons.Close]：Model3dViewerScreen 收起控制面板的 ×（新增图标）；
 *  - [LogisticsIcons.Check]：MaterialDetailScreen / OrderDetailScreen 的成功标记；
 *  - `fromSymbol("☰")`：Model3dViewerScreen 展开控制面板的菜单图标走既有翻译表。
 */
class LogisticsIconsCoverageTest {

    @Test
    fun closeIconExistsForViewerCollapseButton() {
        assertNotNull(LogisticsIcons.Close)
        assertEquals("Close", LogisticsIcons.Close.name)
    }

    @Test
    fun checkIconBacksSuccessBadges() {
        assertNotNull(LogisticsIcons.Check)
        assertEquals(LogisticsIcons.Check, LogisticsIcons.fromSymbol("✓"))
    }

    @Test
    fun menuSymbolTranslatesToVector() {
        assertEquals(LogisticsIcons.List, LogisticsIcons.fromSymbol("☰"))
    }
}
