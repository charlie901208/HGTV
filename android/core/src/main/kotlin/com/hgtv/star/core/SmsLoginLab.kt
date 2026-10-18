// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.File

/**
 * [PhoneLogin] 的命令行入口（Probe 的 `--sms-send` / `--sms-verify`），在电脑上排查用。
 *
 * 两步分两次进程跑，中间状态（设备身份、Cookie、`mobile_ticket`）存在 `build/sms-lab.json`，
 * 过程记在 `build/sms-lab.log`，都不入库。
 */
internal object SmsLoginLab {
    private val STATE=File("build/sms-lab.json")
    private val COOKIE_OUT=File("build/sms-lab-cookie.txt")
    private val LOG=File("build/sms-lab.log")
    private val http=OkHttpClient()

    fun send(raw: String,type: String) = use {
        val login=PhoneLogin(http).apply { this.type=type }
        try {
            val sent=login.send(PhoneLogin.parse(raw))
            say("注册设备：device_id=${login.deviceId} install_id=${login.installId}")
            say("验证码已发到 ${sent.maskedMobile}。收到后执行：--sms-verify <验证码>")
        } finally {
            login.trace.forEach(::say)
            save(login)
        }
    }

    fun verify(code: String) = use {
        require(code.matches(Regex("\\d{4,8}"))) { "验证码要 4~8 位数字" }
        check(STATE.exists()) { "先跑 --sms-send <手机号>" }
        val login=load()
        try {
            val credentials=login.verify(code)
            COOKIE_OUT.writeText(credentials.cookie)
            // 有 sessionid 不代表是本人：平台对无效会话会造一个匿名账号（见 AccountProfile.anonymous）。
            val repository=ContentRepository(http).apply { this.credentials=credentials }
            val profile=runCatching { repository.accountProfile() }.getOrNull()
            say("结果：拿到 sessionid；账号=${profile?.nickname ?: "取不到资料"} 红果号=${profile?.displayId.orEmpty()} 匿名=${profile?.anonymous}")
            say("整段 Cookie 已写到 ${COOKIE_OUT.absolutePath}")
        } finally {
            login.trace.forEach(::say)
            save(login)
        }
    }

    private fun save(login: PhoneLogin) = STATE.writeText(JSONObject()
        .put("device_id",login.deviceId).put("iid",login.installId).put("mobile",login.mobile)
        .put("mobile_ticket",login.ticket).put("type",login.type).put("cookies",JSONObject(login.cookies)).toString(2))

    private fun load(): PhoneLogin {
        val state=JSONObject(STATE.readText())
        return PhoneLogin(http).apply {
            deviceId=state.getString("device_id"); installId=state.getString("iid"); mobile=state.getString("mobile")
            ticket=state.optString("mobile_ticket"); type=state.optString("type",PhoneLogin.LOGIN_CODE_TYPE)
            val saved=state.getJSONObject("cookies")
            saved.keys().forEach { cookies[it]=saved.getString(it) }
        }
    }

    /** 同时写一份 UTF-8 日志：Windows 终端是 Big5，经 Gradle 转手后简体字会乱码，看日志最准。 */
    private fun say(line: String) {
        println(line)
        LOG.appendText("${java.time.LocalTime.now().withNano(0)} $line\n")
    }

    private inline fun use(block: () -> Unit) {
        try { block() } catch(problem: Exception) { say("失败：${problem.message}"); throw problem }
        finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
}
