// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 一次可用的更新：信息来自发布仓库的 `update.json`（Release 附件，由发布工作流生成）。
 *
 * 判定「有没有新版」只看 versionCode，不猜版本号字符串；`minSdk` 高于本机 SDK 的版本
 * 不算可用更新（电视继续用当前版本，界面会说明原因）。
 */
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val tag: String,
    val fileName: String,
    val size: Long,
    val sha256: String,
    val notes: String
) {
    fun newerThan(installed: Long, sdk: Int) = versionCode > installed && minSdk <= sdk
    val downloadUrl get() = "$RELEASES/download/$tag/$fileName"

    companion object {
        const val REPOSITORY = "charlie901208/HGTV"
        const val PACKAGE = "com.hgtv.star"
        const val RELEASES = "https://github.com/$REPOSITORY/releases"
        /** 元数据地址：Release 的 latest 附件，改仓库只改 [REPOSITORY]。 */
        const val METADATA_URL = "$RELEASES/latest/download/update.json"
        const val MAX_APK_BYTES = 80L * 1024 * 1024
        const val MAX_METADATA_BYTES = 64 * 1024
        const val MAX_NOTES = 1500

        private val VERSION = Regex("[0-9][0-9A-Za-z.+-]{0,63}")
        private val TAG = Regex("v[0-9][0-9A-Za-z.+-]{0,80}")
        private val APK = Regex("HGTV_[0-9][0-9A-Za-z.+-]{0,80}\\.apk")
        private val SHA256 = Regex("[0-9a-f]{64}")

        /** 解析并逐字段校验；任何一项不对就抛 [IOException]，调用方按「更新信息不可用」处理。 */
        fun parse(json: String): AppUpdate {
            val root = try { JSONObject(json) } catch (_: Exception) { throw IOException("更新信息格式不正确") }
            if (root.optInt("schema") != 1 || root.optString("packageName") != PACKAGE) throw IOException("更新信息不匹配")
            val apk = root.optJSONObject("apk") ?: throw IOException("更新信息缺少安装包")
            val update = AppUpdate(
                versionCode = root.optLong("versionCode"),
                versionName = root.optString("versionName"),
                minSdk = root.optInt("minSdk"),
                tag = root.optString("tag"),
                fileName = apk.optString("name"),
                size = apk.optLong("size"),
                sha256 = apk.optString("sha256").lowercase(),
                notes = root.optString("notes").take(MAX_NOTES)
            )
            if (update.versionCode !in 1..Int.MAX_VALUE.toLong()) throw IOException("更新信息里的版本号不可用")
            if (update.minSdk !in 26..100) throw IOException("更新信息里的最低系统版本不可用")
            if (!VERSION.matches(update.versionName)) throw IOException("更新信息里的版本名不可用")
            if (!TAG.matches(update.tag)) throw IOException("更新信息里的版本标签不可用")
            if (!APK.matches(update.fileName)) throw IOException("更新信息里的安装包名不可用")
            if (update.size !in 1..MAX_APK_BYTES) throw IOException("更新信息里的安装包大小不可用")
            if (!SHA256.matches(update.sha256)) throw IOException("更新信息里的校验值不可用")
            return update
        }
    }
}

/** 安装包的落盘与校验：流式接收，边收边算，先写 `.part` 再改名，失败不留半截文件。 */
object UpdateFiles {
    fun verify(file: File, update: AppUpdate): Boolean {
        if (!file.isFile || file.length() != update.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == update.sha256
    }

    /** 把 [input] 收进 [destination]；长度超出 [AppUpdate.size] 立即中止，避免磁盘被写满。 */
    fun receive(input: InputStream, destination: File, update: AppUpdate, progress: (Int) -> Unit = {}) {
        val partial = File(destination.parentFile, destination.name + ".part")
        destination.parentFile?.mkdirs()
        try {
            partial.outputStream().use { output ->
                val buffer = ByteArray(32768)
                var total = 0L
                var last = -1
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("下载已取消")
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > update.size) throw IOException("安装包长度异常")
                    output.write(buffer, 0, n)
                    val percent = (total * 100 / update.size).toInt()
                    if (percent != last) { last = percent; progress(percent) }
                }
                output.fd.sync()
            }
            if (!verify(partial, update)) throw IOException("安装包校验未通过，请重新下载")
            if (!partial.renameTo(destination)) throw IOException("无法保存安装包")
        } finally {
            partial.delete()
        }
    }
}

/**
 * 更新仓库的读取端。只允许 HTTPS 且只跟随 GitHub 的下载跳转，
 * 不会去请求元数据里给的任意主机。
 */
class AppUpdateRepository : java.io.Closeable {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .addNetworkInterceptor { chain ->
            val url = chain.request().url
            val allowed = url.scheme == "https" && url.host in setOf(
                "github.com", "api.github.com",
                "release-assets.githubusercontent.com", "objects.githubusercontent.com"
            )
            if (!allowed) throw IOException("更新下载地址不受支持")
            chain.proceed(chain.request())
        }
        .build()

    private fun request(url: String) = Request.Builder().url(url)
        .header("User-Agent", "HGTV-Updater")
        .header("Cache-Control", "no-cache")
        .build()

    /** 读最新一次发布的元数据；没有发布过时给一句可读的提示，而不是空指针。 */
    fun latest(): AppUpdate {
        val call = http.newCall(request(AppUpdate.METADATA_URL))
        call.timeout().timeout(25, TimeUnit.SECONDS)
        return call.execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(if (response.code == 404) "尚无可用的更新版本" else "无法检查更新，请稍后重试")
            }
            val body = response.body ?: throw IOException("更新信息为空")
            val text = body.byteStream().use { read(it, AppUpdate.MAX_METADATA_BYTES, "更新信息过大") }
            AppUpdate.parse(text.toString(Charsets.UTF_8))
        }
    }

    fun download(update: AppUpdate, file: File, progress: (Int) -> Unit) {
        if (UpdateFiles.verify(file, update)) return
        http.newCall(request(update.downloadUrl)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("安装包下载失败，请稍后重试")
            val body = response.body ?: throw IOException("安装包为空")
            val declared = body.contentLength()
            if (declared >= 0 && declared != update.size) throw IOException("安装包长度异常")
            body.byteStream().use { UpdateFiles.receive(it, file, update, progress) }
        }
    }

    fun cancel() = http.dispatcher.cancelAll()

    override fun close() {
        cancel()
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    private fun read(input: InputStream, limit: Int, tooLarge: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > limit) throw IOException(tooLarge)
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
