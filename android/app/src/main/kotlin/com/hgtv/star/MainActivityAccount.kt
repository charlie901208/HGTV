// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hgtv.star.core.*
import java.util.concurrent.Executors
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「账号参数导入（本机文件 / 手机扫码）与设备注册」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.pickAccountFile() {
    val intent=android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(android.content.Intent.CATEGORY_OPENABLE)
        type="*/*"
        putExtra(android.content.Intent.EXTRA_MIME_TYPES,arrayOf("text/xml","application/xml","text/plain","*/*"))
    }
    runCatching { startActivityForResult(intent,REQUEST_ACCOUNT_FILE) }
        .onFailure { tvTools.info("本机文件","这台设备上没有可用的文件选择器。可以改用「手机扫码」上传。",null) }
}
/**
 * 读回选中的文件内容并导入（在 onResume 收尾之后调用，见 [pickAccountFile] 的说明）。
 *
 * 大小上限与手机上传那条路一致（[AccountImportServer.MAX_BYTES]）：`cookieStore.xml`
 * 实测约 180 KB，超限的肯定不是这个文件。
 */
internal fun MainActivity.consumePendingAccountFile() {
    val uri=pendingAccountUri ?: return
    pendingAccountUri=null
    io.submit {
        val text=runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() }?.toString(Charsets.UTF_8) }
        main.post {
            if(isDestroyed) return@post
            text.onSuccess { value ->
                if(value.isNullOrBlank()) tvTools.info("导入失败","文件是空的，或读不到内容。",null)
                else importAccount(value,null)
            }.onFailure { failure ->
                tvTools.info("导入失败","读取文件失败：${failure.message.orEmpty().take(120)}",null)
            }
        }
    }
}
/**
 * 导入账号参数：把手机上送来的文本（cookieStore.xml 或整段 Cookie）解析成凭据并存到本机。
 *
 * 解析在后台线程跑（XML 最大约 500 KB），解析结果立刻做一次账号校验：
 * 通过就刷新「我的」页与首页两栏；不通过就如实提示，**不写入**本机（免得留一份坏凭据）。
 *
 * **不走 [work]**：与 [loadAccount] 同一个理由——`work` 的回调会被任何一次
 * [invalidatePage] 丢掉，导入这条路上恰好每次都会经过一次页面重建。
 */
internal fun MainActivity.importAccount(text: String,anchor: View?) {
    showHint("正在验证账号参数…")
    io.submit {
        val result=runCatching { parseCredentials(text) }
        main.post {
            if(isDestroyed) return@post
            result.onSuccess { credentials -> adoptAccount(credentials,"账号参数已导入") }
                .onFailure { failure -> tvTools.info("导入失败",failure.message.orEmpty().take(200),anchor) }
        }
    }
}
/** 校验通过的凭据：存到本机，刷新「我的」页与首页两栏。导入与手机号登录共用。 */
internal fun MainActivity.adoptAccount(credentials: AccountCredentials,hint: String) {
    library.saveAccount(credentials)
    applyAccount()
    showHint(hint)
    showCatalog()
    loadAccount(force=true)
}
/**
 * 手机号登录：选地区 → 填号码发码 → 填验证码登录（协议见 [PhoneLogin]）。
 *
 * 每次发码都是一个新的 [PhoneLogin]（新注册一台设备），验证码只对最近一次发码有效。
 * 发码或登录失败就回到同一步，并把平台给的原因显示在输入框下面。
 * 每一步的平台原文打到 logcat（标签 `HGTV`），排查时用 `adb logcat -s HGTV` 看。
 */
internal fun MainActivity.startPhoneLogin(anchor: View?) {
    tvTools.phoneRegion(anchor) { countryCode -> askPhoneNumber(anchor,countryCode,null) }
}
private fun MainActivity.askPhoneNumber(anchor: View?,countryCode: String?,error: String?) {
    tvTools.phoneNumber(anchor,countryCode,error) { mobile ->
        showHint("正在发送验证码…")
        val login=PhoneLogin(repository.http)
        io.submit {
            val result=runCatching { login.send(mobile) }
            logPhoneLogin(login)
            main.post {
                if(isDestroyed) return@post
                result.onSuccess { sent -> askPhoneCode(anchor,login,sent.maskedMobile,null) }
                    .onFailure { failure -> askPhoneNumber(anchor,countryCode,failure.message.orEmpty().take(200)) }
            }
        }
    }
}
private fun MainActivity.askPhoneCode(anchor: View?,login: PhoneLogin,sentTo: String,error: String?) {
    tvTools.phoneCode(anchor,sentTo,error) { code ->
        showHint("正在登录…")
        io.submit {
            val result=runCatching {
                val credentials=login.verify(code)
                // 与导入同一道关：平台对无效会话不报错，只降级成匿名设备（见 parseCredentials）。
                repository.credentials=credentials
                val profile=repository.accountProfile()
                require(profile!=null && !profile.anonymous) { "平台下发了登录态，但没有认出账号。请按返回键重新发送验证码。" }
                credentials
            }
            logPhoneLogin(login)
            main.post {
                if(isDestroyed) return@post
                result.onSuccess { credentials -> adoptAccount(credentials,"手机号登录成功") }
                    .onFailure { failure ->
                        // 没登成就退回本机原来那份凭据（没有就是未登录）。
                        repository.credentials=library.account().takeIf { it.configured }
                        askPhoneCode(anchor,login,sentTo,failure.message.orEmpty().take(200))
                    }
            }
        }
    }
}
private fun logPhoneLogin(login: PhoneLogin) {
    login.trace.forEach { android.util.Log.i("HGTV","phone-login $it") }
    login.trace.clear()
}
/**
 * 把手机上送来的文本解析成凭据：`cookieStore.xml` 走 XML 解析，其余按 `key=value` 串处理。
 *
 * 解析完立刻做一次账号校验：通过才写入本机，免得留一份坏凭据。
 */
internal fun MainActivity.parseCredentials(text: String): AccountCredentials {
    val trimmed=text.trim()
    // `device_profile.json`（dump_device.py 的产物）单独走一条路：它没有 Cookie，
    // 只把设备档案存下来，签名（x-medusa）要用。
    if(trimmed.startsWith("{") && trimmed.contains("device_id")) {
        val device=AccountCredentialsParser.parseDeviceProfile(trimmed)
        require(device["device_id"].orEmpty().isNotBlank()) { "这份档案里没有 device_id，确认传的是 device_profile.json" }
        library.saveAccountDevice(device)
        val current=library.account()
        require(current.configured) { "设备档案已保存。但还没有账号参数，请再传一份 cookieStore.xml。" }
        repository.credentials=current
        return current
    }
    val xml=trimmed.contains("<map")
    val credentials=if(xml) {
        AccountCredentialsParser.parseCredentials(trimmed,library.accountDevice())
    } else {
        // 「输入 sessionid」那一条路送来的可能是纯值、`sessionid=…`、整段 Cookie，
        // 也可能是 `cookieStore.xml` 里那一整行（或它的十六进制值本身）——
        // 统一由 [AccountCredentialsParser.sessionIdOf] 归一。
        val id=AccountCredentialsParser.sessionIdOf(trimmed)
        require(AccountCredentialsParser.looksLikeSessionId(id)) {
            if(id.isEmpty()) "没有解析到 sessionid" else "这不是一个 sessionid（要 32 位十六进制）"
        }
        AccountCredentialsParser.parseHeader("sessionid=$id")
    }
    require(credentials.configured) { "没有解析到 sessionid，请确认上传的是红果 App 的 cookieStore.xml" }
    repository.credentials=credentials
    val profile=repository.accountProfile()
    require(profile!=null) { "平台没有返回账号资料，参数可能已过期（cookieStore.xml 要取最新的那一份）" }
    // 平台对无效 / 过期的 sessionid **不报错**，只把请求降级成匿名设备并返回一个临时账号。
    // 不挡住的话，一份坏凭据会「导入成功」，而界面上只是一条记录都没有——
    // 这正是「导入后没有任何记录」最常见的原因。
    require(!profile.anonymous) {
        "这份 sessionid 无效或已过期：平台把它当成了匿名设备（返回的账号是「${profile.nickname}」）。\n" +
        "请重新从红果 App 取一份最新的 cookieStore.xml。"
    }
    return credentials
}
/**
 * 拉取账号数据（资料 + 四条列表），成功后重建「我的」页与首页两栏。
 *
 * **不走 [work]**：`work` 的回调挂在「当前页次」上，任何一次 [invalidatePage]
 * （切页、重建、回前台时的 showCatalog）都会把在途的回调丢掉；而 [accountLoading]
 * 是在回调里复位的，于是它一旦被丢掉就永远停在 true——之后每次进这一页都直接早退，
 * 界面上就是「一直显示正在读取，很久没有结果」。这里自己管一个序号：
 * 只有最后一次请求的回调会落地，且无论成功失败都会把 [accountLoading] 放回去。
 */
internal fun MainActivity.loadAccount(force: Boolean=false) {
    if(!repository.signedIn) return
    if(accountLoading && !force) return
    accountLoading=true
    val token=++accountToken
    io.submit {
        val result=runCatching { repository.accountData() }
        main.post {
            if(isDestroyed || token!=accountToken) return@post
            accountLoading=false
            result.onSuccess { data -> accountData=data; accountError=null }
                .onFailure { failure ->
                    accountError=failure
                    // 需要登录态的接口失败时给一条短提示（见 [accountFailureText]）：
                    // 这一页本身会把原因写在内容区，但用户可能正在别的页面等着结果，
                    // 那种「按了没反应」的体感只能靠这条即时反馈补上。
                    accountFailureText(failure)?.let { tvTools.toast(it) }
                }
            // 这三处都要重画，因为它们的列表都是**本机 + 账号**合并出来的：
            // 「我的」页（四个栏目就是这份数据）、首页那两栏、以及本机记录集合页
            // （收藏 / 历史 / 稍后观看）。少了集合页这一支，先进集合页再等账号数据落地时
            // 那一页会停在只有本机记录的状态——就是「收藏界面的数量和首页对不上」。
            val merged=collection!=null || tab==TAB_PROFILE || (tab==TAB_HOME && screen=="catalog")
            if(merged) showCatalog()
        }
    }
}
/**
 * 账号参数变化后同步到仓库。
 *
 * 首页「我的收藏」「历史记录」两栏是**本机 + 账号**合并的结果（见 [historyRows] / [favoriteRows]），
 * 因此这里只把凭据交给仓库，首页重建时自然带上账号里的条目。
 */
internal fun MainActivity.applyAccount() {
    // 设备身份：只有平台注册下发的那一对（`deviceIdentity()` 没注册过时是空表，不再自己编随机值）。
    // 随后异步补一次注册（匿名）：拿到之后搜索与播放都走这同一份身份。
    repository.registerProfile=library.deviceProfile()
    repository.deviceIdentity=library.deviceIdentity()
    repository.credentials=library.account().takeIf { it.configured }
    ensureDeviceRegistered()
    accountData=null; accountError=null
    // 换凭据就把在途的那一次作废，并把「正在读」放回去：否则新凭据要等旧请求的超时才发。
    accountToken++
    accountLoading=false
}
/**
 * 确保本机有一台**平台注册过的**设备；没有就注册一台并落盘。
 *
 * 注册是匿名的（不需要账号 / 密码 / 短信），一次请求即可。它在后台跑，
 * 不阻塞首屏；成功之后把身份交给仓库，后续搜索与播放都走 App 接口那条快路径。
 * 失败不弹窗——搜索会退回官网搜索、播放会报「暂不可用」，下次启动再试。
 */
internal fun MainActivity.ensureDeviceRegistered() {
    if(library.deviceRegistered()) return
    if(deviceRegistering) return
    deviceRegistering=true
    // 用 io 池直接跑，**不走 [work]**：那个入口会 cancelWork()，把在途的页面请求一起掐掉。
    io.submit {
        val registered=registerDeviceOnce()
        main.post {
            deviceRegistering=false
            if(!registered || isDestroyed) return@post
            // 注册成功后把身份重新交给仓库，并重新拉一次首页——
            // 首屏很可能已经用「没注册」的身份请求过了（App 搜索会空响应）。
            repository.deviceIdentity=library.deviceIdentity()
            if(foreground && screen=="home") showCatalog(load=true)
        }
    }
}
/**
 * 注册一次并落盘（**并发调用只发一次请求**），成功返回 true。
 *
 * 内容接口（App 搜索 / 播放 / 弹幕）认的是**平台注册过的** `device_id` + `install_id`，
 * 本机不再自己编随机身份，因此「用之前先确保注册过」只有这一处实现：
 * 启动时异步调一次（[ensureDeviceRegistered]），播放取地址之前再同步兜一次
 * （见 [playEpisode] 的取地址那一段与 [maybePrefetch]）。
 */
internal fun MainActivity.registerDeviceOnce(): Boolean {
    synchronized(deviceRegisterLock) {
        if(library.deviceRegistered()) return true
        val registered=runCatching { repository.registerDevice() }.getOrNull() ?: return false
        library.saveDeviceIdentity(registered.deviceId,registered.installId)
        repository.deviceIdentity=library.deviceIdentity()
        return true
    }
}
