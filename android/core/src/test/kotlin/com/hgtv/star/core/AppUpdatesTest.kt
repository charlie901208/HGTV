package com.hgtv.star.core

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class AppUpdatesTest {

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun payload(bytes: ByteArray, versionCode: Long = 30, fileName: String = "HGTV_1.0.1.apk") = JSONObject()
        .put("schema", 1)
        .put("packageName", AppUpdate.PACKAGE)
        .put("versionCode", versionCode)
        .put("versionName", "1.0.1")
        .put("minSdk", 26)
        .put("tag", "v1.0.1")
        .put("notes", "修了一些问题")
        .put("apk", JSONObject().put("name", fileName).put("size", bytes.size).put("sha256", sha256(bytes)))
        .toString()

    @Test fun `valid metadata parses and newerThan respects both version code and min sdk`() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val update = AppUpdate.parse(payload(bytes))
        assertEquals(30L, update.versionCode)
        assertEquals("HGTV_1.0.1.apk", update.fileName)
        assertEquals("https://github.com/charlie901208/HGTV/releases/download/v1.0.1/HGTV_1.0.1.apk", update.downloadUrl)
        assertTrue(update.newerThan(24, 26))
        assertFalse("同版本不算更新", update.newerThan(30, 26))
        assertFalse("更高 minSdk 不算可用更新", update.newerThan(24, 25))
    }

    @Test fun `metadata is rejected when any field is unusable`() {
        val bytes = ByteArray(16)
        val bad = listOf(
            "空内容" to "",
            "不是 JSON" to "not json",
            "schema 不对" to JSONObject(payload(bytes)).put("schema", 2).toString(),
            "包名不对" to JSONObject(payload(bytes)).put("packageName", "com.other.app").toString(),
            "版本名为空" to JSONObject(payload(bytes)).put("versionName", "").toString(),
            "版本名带空格" to JSONObject(payload(bytes)).put("versionName", "1.0.1 beta").toString(),
            "tag 前缀不对" to JSONObject(payload(bytes)).put("tag", "kotlin-v1.0.1").toString(),
            "安装包名不是本仓库命名" to payload(bytes, fileName = "app-release.apk"),
            "安装包名带路径" to payload(bytes, fileName = "../HGTV_1.0.1.apk"),
            "校验值不是 64 位十六进制" to JSONObject(payload(bytes))
                .put("apk", JSONObject().put("name", "HGTV_1.0.1.apk").put("size", bytes.size).put("sha256", "abc")).toString(),
            "缺少 apk 段" to JSONObject(payload(bytes)).remove("apk").toString()
        )
        bad.forEach { (why, json) ->
            try {
                AppUpdate.parse(json)
                fail("应当拒绝：$why")
            } catch (expected: IOException) {
                // 期望：坏数据一律转成可显示的 IOException，而不是抛别的异常或崩掉
            }
        }
    }

    @Test fun `receive writes the file only when size and digest match`() {
        val bytes = ByteArray(70000) { (it % 97).toByte() }
        val update = AppUpdate.parse(payload(bytes))
        val dir = File(System.getProperty("java.io.tmpdir"), "hgtv-update-test-" + System.nanoTime())
        dir.mkdirs()
        try {
            val target = File(dir, "update.apk")
            val seen = mutableListOf<Int>()
            UpdateFiles.receive(ByteArrayInputStream(bytes), target, update) { seen += it }
            assertTrue("下载完成后文件存在", target.isFile)
            assertEquals(bytes.size.toLong(), target.length())
            // 进度按「百分比变化」上报：首块 32768/70000 ≈ 46%，因此第一次不一定从 0 开始，
            // 但必须单调不减、以 100 收尾。
            assertTrue("进度单调不减", seen == seen.sorted())
            assertEquals("进度收尾在 100", 100, seen.last())
            assertTrue("进度不重复上报同一个值", seen.distinct().size == seen.size)
            assertTrue("落盘文件通过校验", UpdateFiles.verify(target, update))
            assertFalse("没有残留 .part", File(dir, "update.apk.part").exists())

            // 截断的响应：不能留下半截文件，也不能通过校验
            val truncated = File(dir, "truncated.apk")
            try {
                UpdateFiles.receive(ByteArrayInputStream(bytes.copyOf(bytes.size - 1000)), truncated, update)
                fail("应当因校验失败而中止")
            } catch (expected: IOException) { }
            assertFalse("截断的下载不留文件", truncated.exists())
            assertFalse(UpdateFiles.verify(truncated, update))

            // 超长响应：提前中止，不把磁盘写满
            val oversize = File(dir, "oversize.apk")
            val tooMuch = ByteArray(bytes.size + 4096)
            bytes.copyInto(tooMuch)
            try {
                UpdateFiles.receive(ByteArrayInputStream(tooMuch), oversize, update)
                fail("应当在超出声明长度时中止")
            } catch (expected: IOException) { }
            assertFalse(oversize.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `verify rejects a file whose bytes or length differ`() {
        val bytes = ByteArray(2048) { it.toByte() }
        val update = AppUpdate.parse(payload(bytes))
        val dir = File(System.getProperty("java.io.tmpdir"), "hgtv-update-verify-" + System.nanoTime())
        dir.mkdirs()
        try {
            val good = File(dir, "good.apk").apply { writeBytes(bytes) }
            assertTrue(UpdateFiles.verify(good, update))
            val tampered = File(dir, "tampered.apk").apply { writeBytes(bytes.copyOf().also { it[0] = (it[0] + 1).toByte() }) }
            assertFalse("改一个字节就不过", UpdateFiles.verify(tampered, update))
            val short = File(dir, "short.apk").apply { writeBytes(bytes.copyOf(bytes.size - 1)) }
            assertFalse("长度不对不过", UpdateFiles.verify(short, update))
            assertFalse("文件不存在不过", UpdateFiles.verify(File(dir, "missing.apk"), update))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `notes longer than the limit are truncated instead of rejected`() {
        val bytes = ByteArray(64)
        val long = JSONObject(payload(bytes)).put("notes", "x".repeat(5000)).toString()
        assertEquals(AppUpdate.MAX_NOTES, AppUpdate.parse(long).notes.length)
    }
}
