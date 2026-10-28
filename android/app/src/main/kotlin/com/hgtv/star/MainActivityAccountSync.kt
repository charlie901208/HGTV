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
import com.hgtv.star.list.seriesList
import com.hgtv.star.list.showCatalog
import com.hgtv.star.data.WatchProgress

// 本文件是从 MainActivity.kt 拆出来的「收藏 / 进度的账号侧同步（本机改动推到账号，账号数据落到本机）」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.syncFavorite(series: Series,added: Boolean) {
    applyAccountFavorite(series,added)
    // 收藏是**需要登录态**的动作（云端那一半走 App 接口）：没导入凭据就直说，
    // 不再像以前那样静默放弃——用户看到的会是「点了收藏、界面变了、云端却没变」。
    if(!repository.signedIn) { tvTools.toast(SIGN_IN_TOAST); return }
    io.submit {
        val ok=if(added) repository.accountAddFavorite(series.id) else repository.accountRemoveFavorite(series.id)
        main.post {
            if(isDestroyed) return@post
            if(!ok) tvTools.toast(if(added) "收藏同步失败，稍后再试" else "取消收藏同步失败，稍后再试")
        }
        if(!ok) android.util.Log.i("HGTV","Favorite sync skipped id=${series.id} added=$added")
    }
}
/** 上传一条观看进度；[episodeCount] 只用于云端「全 N 集」的展示。 */
internal fun MainActivity.syncProgress(progress: WatchProgress,episodeCount: Int=0) {
    applyAccountHistory(progress,episodeCount)
    // 观看进度是**需要登录态**的（`read_progress/upload`）。没导入凭据时不弹提示：
    // 它在播放过程中会反复触发，每次都弹一条会把画面刷满；「我的」页与收藏那两处
    // 已经能把「需要导入」这件事说清楚。
    if(!repository.signedIn) return
    io.submit {
        val ok=repository.accountUploadProgress(progress.series.id,progress.episodeId,progress.episodeIndex,
            progress.position,progress.duration,episodeCount)
        if(!ok) android.util.Log.i("HGTV","Progress sync skipped id=${progress.series.id}")
    }
}
/** 删除云端观看历史（`read_history/update` 软删）。 */
internal fun MainActivity.syncHistoryDelete(series: Series) {
    applyAccountHistoryDelete(series)
    if(!repository.signedIn) return
    io.submit {
        val ok=repository.accountDeleteHistory(series.id)
        if(!ok) android.util.Log.i("HGTV","History delete sync skipped id=${series.id}")
    }
}
/** 清空历史时把账号侧那一半也去掉，并逐条通知云端（本机那半由 [Library.clearHistory] 处理）。 */
internal fun MainActivity.syncHistoryClear() {
    val removed=accountData?.history?.map { it.series.id }.orEmpty()
    if(removed.isEmpty()) return
    accountData=accountData?.copy(history=emptyList())
    refreshAccountListIfVisible()
    if(!repository.signedIn) return
    io.submit { removed.forEach { id -> repository.accountDeleteHistory(id) } }
}
/**
 * 本机改动就地写回账号侧缓存：收藏加 / 删。
 *
 * 加进来的那条用本机的 [Series]（封面、简介都齐），因此「我的」页与首页不必再联网补元数据。
 * 已经在列表里的剧不重复加，只是保持原样——云端本来就是幂等的。
 */
internal fun MainActivity.applyAccountFavorite(series: Series,added: Boolean) {
    val data=accountData ?: return
    val rows=data.favorites
    val next=if(added) {
        if(rows.any { it.series.id==series.id }) rows
        else listOf(AccountFavorite(series,System.currentTimeMillis(),0))+rows
    } else rows.filterNot { it.series.id==series.id }
    if(next===rows) return
    accountData=data.copy(favorites=next)
    refreshAccountListIfVisible()
}
/** 本机改动就地写回账号侧缓存：观看进度新增 / 更新（同一部剧只留最新一条，排在最前）。 */
internal fun MainActivity.applyAccountHistory(progress: WatchProgress,episodeCount: Int) {
    val data=accountData ?: return
    val row=AccountHistory(progress.series,progress.episodeIndex,progress.episodeId,progress.updatedAt,episodeCount,progress.position)
    accountData=data.copy(history=listOf(row)+data.history.filterNot { it.series.id==progress.series.id })
    refreshAccountListIfVisible()
}
/** 本机改动就地写回账号侧缓存：删除一条观看历史。 */
internal fun MainActivity.applyAccountHistoryDelete(series: Series) {
    val data=accountData ?: return
    val next=data.history.filterNot { it.series.id==series.id }
    if(next.size==data.history.size) return
    accountData=data.copy(history=next)
    refreshAccountListIfVisible()
}
/**
 * 「我的」页正显示着的时候，账号侧列表被本机改动改过，就地重建那一页。
 *
 * 与 [refreshLibraryCards] 的集合页分支同一套做法：全量重铺、尽量把焦点留在原位
 * （删掉的正是当前那张卡时才退到同一下标的下一条）。首页与集合页不用管——
 * 它们下次重建时会自己读新的 [accountData]。
 */
internal fun MainActivity.refreshAccountListIfVisible() {
    if(screen!="catalog" || tab!=TAB_PROFILE) return
    val index=gridItems.map { it.id }.indexOf(catalogFocus).coerceAtLeast(0)
    catalog=accountItems(accountTab); page=1; hasMore=false
    catalogFocus=catalog.getOrNull(index.coerceAtMost((catalog.size-1).coerceAtLeast(0)))?.id.orEmpty()
    rememberScroll(catalogPageKey,catalogScroll?.scrollY ?: 0)
    artwork.cancelPage()
    catalogBody?.let { body -> body.removeAllViews(); seriesList(body,false) }
}
/**
 * 删除一条本机观看记录：选中即删，不再二次确认。
 *
 * 菜单里这一项本身就是明确的删除意图（要先长按 / 按菜单键、再把焦点移到「删除记录」、
 * 最后按确认），再叠一层弹窗只是把遥控器上的操作变成三次。首页历史剧架的「删除记录」
 * 与历史集合页「管理记录 → 删除这条记录」共用这一份实现，两条路径行为一致。
 *
 * 删完就地刷新所在页面——首页重建剧架（历史栏少一部，空了整栏消失并保留相邻卡片的焦点），
 * 集合页重新按本机列表取页。只删观看进度：收藏、稍后观看、已隐藏的剧集都不动。
 */
internal fun MainActivity.deleteHistoryRecord(series: Series,anchor: View?) {
    library.removeHistory(series.id)
    syncHistoryDelete(series)
    if(collection=="history") { catalogFocus=""; showCatalog() } else refreshLibraryCards(series.id,true)
}
