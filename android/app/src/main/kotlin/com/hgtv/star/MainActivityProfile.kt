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
import com.hgtv.star.tv.ButtonSize
import com.hgtv.star.tv.ButtonTone
import com.hgtv.star.tv.SoftStyle
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「「我的」页：抬头、四个栏目、账号数据与登录提示」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.profileHead(row: LinearLayout) {
    val credentials=library.account().takeIf { it.configured }
    val profile=accountData?.profile
    // 圆形头像：`ArtworkLoader` 只会把位图设进 ImageView，圆角靠一个圆形遮罩的
    // `SoftDrawable` 做不到（那是背景不是前景），因此这里给 ImageView 挂一个圆形裁剪的
    // `ViewOutlineProvider`（API 21+），加 `clipToOutline` 就得到正圆。
    val avatar=ImageView(this).apply {
        scaleType=ImageView.ScaleType.CENTER_CROP
        contentDescription="账号头像"
        clipToOutline=true
        outlineProvider=object: ViewOutlineProvider() {
            override fun getOutline(view: View,outline: android.graphics.Outline) {
                outline.setOval(0,0,view.width,view.height)
            }
        }
        // 还没读到资料时先给一块凹槽色：比空白好认，也不会让这一行忽高忽低。
        background=softPanel(TvStyle.well,TvStyle.RADIUS_PILL,style=SoftStyle.INSET)
    }
    profile?.avatar?.takeIf { it.isNotBlank() }?.let { loadCover(avatar,it) }
    row.addView(avatar,lp(dp(PROFILE_AVATAR),dp(PROFILE_AVATAR)))
    // 昵称小字（比标题小两档）：它是身份标注，不是这一页的主角。
    val name=profile?.nickname?.takeIf { it.isNotBlank() }
        ?: if(credentials==null) "未导入账号参数" else "正在读取…"
    val nameView=text(name,14f,if(credentials==null) muted else TvStyle.textDim).apply { maxLines=1 }
    gapInto(row,nameView,lp(-2,-2),TvStyle.GAP_INLINE)
    // 两个动作按钮：只有导入过参数才给（没登录时它们没有可操作的对象）。
    if(credentials==null) {
        lateinit var phoneButton: TvButton
        phoneButton=addButton(row,"手机号登录",size=ButtonSize.COMPACT) { startPhoneLogin(phoneButton) }
        lateinit var importButton: TvButton
        importButton=addButton(row,"导入账号参数",size=ButtonSize.COMPACT) {
            tvTools.accountImport(importButton,{ text -> importAccount(text,importButton) },{ pickAccountFile() })
        }
        return
    }
    lateinit var reimport: TvButton
    reimport=addButton(row,"重新导入",size=ButtonSize.COMPACT) {
        tvTools.accountImport(reimport,{ text -> importAccount(text,reimport) },{ pickAccountFile() })
    }
    lateinit var clear: TvButton
    clear=addButton(row,"清除",size=ButtonSize.COMPACT) {
        tvTools.confirm("清除账号参数","清除后这一页会回到未导入状态，本机收藏与观看记录不受影响。",reimport) {
            library.clearAccount()
            applyAccount()
            showCatalog()
        }
    }
    // 「重新加载」：凭据没变、只是想重取一次账号数据时用（平台偶发空响应体，
    // 或者另一台设备上刚改过收藏 / 历史）。放在「清除」后面，与它拉开一档间距。
    addButton(row,"重新加载",size=ButtonSize.COMPACT,gap=TvStyle.GAP_BLOCK) {
        accountData=null; accountError=null
        loadAccount(force=true)
        showCatalog()
    }
    // 资料还没到、而且**一次都还没试过**时主动拉一次（落地后页面自己重建）。
    // 失败过就不再自动重发：否则「失败 → showCatalog → 再拉」会变成无限重试，
    // 重试交给下面 [accountNotice] 里那个按钮。
    if(accountData==null && accountError==null) loadAccount()
}
/**
 * 「我的」页当前栏目里的条目（转成 [Series]，直接喂给统一的卡片栅格）。
 *
 * 四栏各自的数据来自 [accountData]；还没拉到（或那一栏为空）时返回空列表，
 * 界面走「本页没有更多内容」那一支的文案。
 */
internal fun MainActivity.accountItems(key: String): List<Series> {
    val data=accountData ?: return emptyList()
    return when(key) {
        "favorites" -> data.favorites.map { it.series }
        "history" -> data.history.map { it.series }
        "likes" -> data.likes.map { it.series }
        "reserves" -> data.reserves.map { it.series }
        else -> emptyList()
    }
}
/**
 * 切换「我的」页的栏目。
 *
 * 与榜单页的芯片同一条规则：焦点留在刚点的那一枚上（用户还在挑栏目），
 * 内容落地后也不许把焦点带去第一张卡。
 */
internal fun MainActivity.switchAccountTab(key: String) {
    if(key==accountTab) return
    accountTab=key
    accountButtons.forEach { (option,chip) -> (chip as? ToggleChip)?.setActive(option==key) }
    // 换栏目就是换一份列表：页码、记住的焦点卡、滚动位置都从头开始。
    page=1; catalogFocus=""
    // 焦点一律留在刚点的那枚芯片上：这一栏的内容就在内存里，切换是「同一页换一份列表」，
    // 用户还在挑栏目，不该被推进内容区（见 [showCatalog] 的 [keepChip]）。
    chipFocusKey="account:$key"
    focusFirstCardOnLanding=false
    showCatalog(keepChip=true)
}
/** 「我的」页身份行里所有可聚焦控件（按钮），按左右顺序。 */
internal fun MainActivity.profileHeadControls(): List<View> {
    val head=profileHeadRow as? LinearLayout ?: return emptyList()
    return (0 until head.childCount).mapNotNull { head.getChildAt(it) }.filter { it.isFocusable }
}
/** 身份行里第一个可聚焦控件；没有（理论上不会）时返回 null，调用方退回别的落点。 */
internal fun MainActivity.profileHeadControl(): View? = profileHeadControls().firstOrNull()
/** 「我的」页的一张凸起面板（未导入 / 读取失败 / 参数无效这三种状态用）。 */
internal fun MainActivity.profilePanel(parent: LinearLayout,title: String): LinearLayout {
    val band=dp(TvStyle.SOFT_BAND_LARGE)
    val panel=column().apply {
        setPadding(dp(24)+band,dp(20)+band,dp(24)+band,dp(20)+band)
        background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,band=TvStyle.SOFT_BAND_LARGE,depth=1.05f)
    }
    if(title.isNotBlank()) panel.addView(text(title,18f,TvStyle.text).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2))
    parent.addView(panel,lp(-1,-2).apply { if(parent.childCount>1) topMargin=dp(TvStyle.GAP_GROUP) })
    return panel
}
/**
 * 账号侧请求失败时给用户看的一句话；**不该打扰的返回 null**。
 *
 * 需要 `sessionid` 的接口（账号资料 / 收藏 / 历史 / 点赞 / 预约，以及带登录态的写接口）
 * 有两条必须说清楚的失败：
 *
 * * **还没导入**（[SignInRequiredException]）——用户不知道这些功能要凭据，得告诉他去哪里导入；
 * * **凭据失效**——平台不报错、只把请求降级成匿名设备，那种情况在导入时就被挡住了
 *   （见 [parseCredentials] 的 `anonymous` 校验），这里只兜底说一句「重新导入」。
 *
 * 其余失败（网络抖动、网关挡掉）不弹：它们会自动重试，弹出来只是噪音。
 */
internal fun MainActivity.accountFailureText(failure: Throwable): String? = when {
    failure is com.hgtv.star.core.SignInRequiredException ->
        "需要先在「我的」页导入账号参数（sessionid）"
    failure.message.orEmpty().contains("空响应体") -> null
    failure is java.net.UnknownHostException -> "网络不可用，账号信息暂时取不到"
    else -> "账号信息读取失败：${failure.message.orEmpty().take(60)}"
}

/**
 * 「我的」页在资料还没拿到（或没通过验证）时，内容区显示什么。
 *
 * 三种情况分开写清楚，**不能停在一句「正在读取」上**：那样用户看到的就是
 * 「点了导入什么也没发生」（这正是之前的症状）。读取失败时把原因与重试都摆出来。
 */
internal fun MainActivity.accountNotice(parent: LinearLayout) {
    val credentials=library.account().takeIf { it.configured }
    // 未导入：**什么都不铺**。入口已经在上面的身份行里（「导入账号参数」按钮），
    // 这里再摆一整块说明只是把页面填满——用户第一眼要看的是按钮，不是说明书。
    if(credentials==null) return
    val problem=accountError
    if(problem!=null) {
        val card=profilePanel(parent,"读取账号信息失败")
        card.addView(text(problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(200)
            .ifBlank { "请检查电视的网络后重试。" },15f,TvStyle.textDim))
        addButton(card,"重试",tone=ButtonTone.PRIMARY) { accountError=null; loadAccount(force=true); showCatalog() }
        return
    }
    val data=accountData
    if(data==null) {
        val card=profilePanel(parent,"正在读取账号信息")
        card.addView(text("正在拉取资料与四个栏目，请稍候。",15f,TvStyle.textDim))
        loadAccount()
        return
    }
    val card=profilePanel(parent,"参数未通过验证")
    val reason=data.errors["资料"]?.takeIf { it.isNotBlank() } ?: "平台没有返回账号资料"
    card.addView(text("$reason\n\n参数可能已过期，或 Cookie 不完整。请用页头的「重新导入」换一份新的 cookieStore.xml。",15f,TvStyle.textDim))
}
internal fun MainActivity.shortDate(value: Long): String = if(value<=0) "时间未知" else java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.getDefault()).format(java.util.Date(value))
/**
 * 打开系统文件选择器挑 `cookieStore.xml`。
 *
 * `ACTION_OPEN_DOCUMENT`（而不是 GET_CONTENT）：它给的是持久可读的 Uri，
 * 电视上从 U 盘 / 下载目录里挑文件更稳，也不要求任何存储权限。
 *
 * **结果不在这里处理**：文件选择器会让本页走一遍 onStop → onResume，而 onResume 里的
 * [showCatalog] 会 `invalidatePage() → cancelWork()`，把紧随其后提交的导入任务一并作废
 * （症状就是「选完文件什么也没发生」）。因此这里只记下 Uri，等 onResume 收尾之后再消费。
 */
