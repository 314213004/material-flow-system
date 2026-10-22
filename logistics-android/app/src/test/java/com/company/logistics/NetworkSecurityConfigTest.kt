package com.company.logistics

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * 批次 2B 项 B：正式包必须收敛明文 HTTP。
 *
 * 验证方式说明（无 Robolectric，用真实文件断言）：
 *  - 直接读 `app/src/main/res/xml/network_security_config.xml`（release 生效的那一份），
 *    用 JDK 的 DocumentBuilder 解析，断言 `base-config/@cleartextTrafficPermitted != "true"`；
 *  - 再对原文做一次字符串断言，确保整个 release 配置里不存在 `cleartextTrafficPermitted="true"`
 *    （domain-config 也不许偷偷放行）；
 *  - 同时断言 debug 源集（`app/src/debug/res/xml/...`）仍是允许明文的那份 ——
 *    这是「内网 IP 直连不被废掉」的实现方式：现场调试/私有部署走 debug/验收包；
 *  - 顺带断言 claws4u.com 的证书锁定（pin-set 2 条 + 有效期）没有回退。
 *
 * Gradle 单测的工作目录是模块目录（app/），这里再向上兜底查找，避免 IDE/JUnit 直跑时找不到文件。
 */
class NetworkSecurityConfigTest {

    private val mainConfig: File by lazy { locate("app/src/main/res/xml/network_security_config.xml") }
    private val debugConfig: File by lazy { locate("app/src/debug/res/xml/network_security_config.xml") }

    @Test
    fun releaseConfigForbidsCleartextGlobally() {
        val root = parse(mainConfig)
        val baseConfig = firstElement(root, "base-config")

        assertNotNull("release 配置缺少 base-config", baseConfig)
        assertEquals(
            "release 的 base-config 必须显式禁止明文",
            "false",
            baseConfig!!.getAttribute("cleartextTrafficPermitted"),
        )
    }

    @Test
    fun releaseConfigHasNoCleartextOptInAnywhere() {
        val text = mainConfig.readText()

        assertFalse(
            "release 生效配置里不得出现 cleartextTrafficPermitted=\"true\"",
            text.contains("cleartextTrafficPermitted=\"true\""),
        )
        // 也不许用单引号/空格等写法绕过断言
        assertFalse(
            "release 生效配置里不得出现任何 cleartext 放行写法",
            Regex("""cleartextTrafficPermitted\s*=\s*['"]\s*true\s*['"]""").containsMatchIn(text),
        )
    }

    @Test
    fun releaseConfigKeepsCertificatePinningForProductionDomain() {
        val root = parse(mainConfig)
        val domainConfigs = root.getElementsByTagName("domain-config")

        var pinned = false
        for (i in 0 until domainConfigs.length) {
            val config = domainConfigs.item(i) as Element
            val domains = config.getElementsByTagName("domain")
            val hasProductionDomain = (0 until domains.length).any {
                (domains.item(it) as Element).textContent.trim() == "claws4u.com"
            }
            if (!hasProductionDomain) continue
            val pinSets = config.getElementsByTagName("pin-set")
            assertEquals("claws4u.com 必须只有一个 pin-set", 1, pinSets.length)
            val pinSet = pinSets.item(0) as Element
            assertTrue("pin-set 必须带有效期", pinSet.getAttribute("expiration").isNotBlank())
            val pins = pinSet.getElementsByTagName("pin")
            assertTrue("证书锁定不得回退：至少保留 2 条 pin", pins.length >= 2)
            pinned = true
        }
        assertTrue("release 配置必须保留 claws4u.com 证书锁定", pinned)
    }

    @Test
    fun debugSourceSetStillAllowsIntranetCleartextForFieldDebugging() {
        assertTrue("debug 覆盖配置必须存在（内网明文直连依赖它）", debugConfig.isFile)
        val root = parse(debugConfig)
        val baseConfig = firstElement(root, "base-config")

        assertNotNull("debug 配置缺少 base-config", baseConfig)
        assertEquals(
            "debug/验收包需要保留内网明文直连能力",
            "true",
            baseConfig!!.getAttribute("cleartextTrafficPermitted"),
        )
    }

    @Test
    fun debugOverrideLivesInDebugSourceSetSoReleaseCannotMergeIt() {
        // 覆盖文件必须在 src/debug 下：release 只合并 main，天然拿不到 cleartext 放行。
        val path = debugConfig.absolutePath.replace('\\', '/')
        assertTrue("debug 覆盖必须位于 src/debug 源集: $path", path.contains("/app/src/debug/"))
        assertFalse("main 源集不得含 cleartext 放行", mainConfig.absolutePath == debugConfig.absolutePath)
    }

    private fun parse(file: File): Element {
        assertTrue("配置文件不存在: ${file.absolutePath}", file.isFile)
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun firstElement(root: Element, tag: String): Element? =
        root.getElementsByTagName(tag).item(0) as? Element

    /** 从当前工作目录向上找 `app/src/...`（兼容 gradle 单测与 IDE 直跑）。 */
    private fun locate(relative: String): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $relative（从 ${System.getProperty("user.dir")} 向上查找）")
    }
}
