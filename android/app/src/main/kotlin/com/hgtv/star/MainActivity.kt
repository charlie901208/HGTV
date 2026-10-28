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
import com.hgtv.star.data.AppUpdater
import com.hgtv.star.data.ArtworkLoader
import com.hgtv.star.player.DanmakuView
import com.hgtv.star.player.EpisodePanel
import com.hgtv.star.data.FavoriteMonitor
import com.hgtv.star.list.HomeScreen
import com.hgtv.star.data.Library
import com.hgtv.star.player.PlaybackMenu
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.SeekIndicator
import com.hgtv.star.tv.Soft
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvDialog
import com.hgtv.star.player.TvMediaSession
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.TvTheme
import com.hgtv.star.tv.TvThemeStore
import com.hgtv.star.tv.TvTools
import com.hgtv.star.list.gridFocusIndex
import com.hgtv.star.list.prefetchNextPage
import com.hgtv.star.list.showCatalog

/**
 * 全站焦点原则：**不主动抢焦点**。
 *
 * 只有**用户自己的动作**可以指定焦点落点：
 *
 * * 点 / 按了一枚芯片（榜单、分类、我的页的四个栏目）→ 焦点留在那一枚上；
 * * 点了「展示更多」→ 焦点落到新出现的第一张卡；
 * * 点了播放 / 收藏 / 完整简介 → 焦点按这次动作的去向落（进播放器、回到同一枚按钮）；
 * * 显式带着落点意图的重建（`focusNav` / `focusType` / `focusFirstCardOnLanding`）。
 *
 * 除此之外的一切——列表重建、异步数据落地（账号数据、封面、剧集数）、空页兜底、
 * 位置恢复、弹窗关闭——**一律不许动焦点**。这类场合用 [ensureFocus] / [ensureFocusView]：
 * 只有当焦点**真的丢了**（持有焦点的控件已被摘掉）才补一个确定的落点，
 * 否则保持用户正按着的地方不动。
 *
 * 违反这条原则的症状是同一类：用户没按方向键，焦点却跳走了——「切栏目焦点跑进内容区」
 * 「收藏后焦点跑到上面」「内容落地后焦点从芯片跳到第一张卡」都是它。
 */


@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    /*
     * 本类的代码按功能拆在 MainActivity* 这一组文件里（本文件保留：状态字段、生命周期、
     * 返回键与系统回调）。拆出去的那部分写成了 MainActivity 的扩展成员，因此它们在同一个
     * 包、同一个模块内互相可见——被别的文件用到的字段 / 方法在这里一律是 internal，
     * 只有确实只在本文件内部使用的才是 private（例如 goBack / confirmExit / tick）。
     *
     * 也就是说：**出现在别的 MainActivity* 文件里的东西不能改回 private**，否则编译不过。
     * 反过来说，看一眼可见性就知道这个字段有没有跨文件共享，排错时省一步全局搜索。
     */
    // 主题可以在设置里随时切换，因此这几个高频令牌写成 getter 而不是构造期快照：
    // 换主题之后重建页面，新控件立刻读到新色值（旧控件的背景 Drawable 会被整页重建换掉）。
    internal val bg get()=TvStyle.background
    internal val white get()=TvStyle.text
    internal val muted get()=TvStyle.muted
    /** 设置页里主题外观分组的四行：换主题后重建页面时按 [TvTheme] 重新填充。 */
    internal val themeRows=linkedMapOf<TvTheme,TvButton>()
    internal val main=Handler(Looper.getMainLooper())
    internal val io=Executors.newFixedThreadPool(3)
    internal val repository=ContentRepository().apply {
        // 写接口（收藏 / 进度 / 历史）的诊断日志：`core` 是纯 Kotlin 模块，日志出口由这里接上。
        writeLog={ line -> android.util.Log.i("HGTV",line) }
    }
    internal val networkCleanup=Executors.newSingleThreadExecutor()
    internal val prefetchWorker=Executors.newSingleThreadExecutor()
    /** 弹幕单独一条线程：它要按 30 秒窗口连着拉几页，不能占住内容请求的线程池。 */
    internal val danmakuWorker=Executors.newSingleThreadExecutor()
    /** 每次换集自增；在途的弹幕请求发现 token 变了就立刻收手。 */
    internal val danmakuToken=java.util.concurrent.atomic.AtomicLong(0)
    internal var prefetchHttp: okhttp3.OkHttpClient?=null
    internal var playbackHttp: okhttp3.OkHttpClient?=null
    internal val preparedNext=PreparedSlot<RemoteVideo>({ android.os.SystemClock.elapsedRealtime() })
    internal var prefetchJob: java.util.concurrent.Future<*>?=null
    internal var prefetchScope: RequestScope?=null
    internal var prefetchedFor=""
    internal var readySince=0L
    /**
     * 无感切集（见 [playEpisode] 里的 `seamless`）：下一集已经预热好时，切集不清播放器，
     * 新源直接顶替旧源，画面停在上一集最后一帧上等下一集的第一帧。
     *
     * [bufferingSuppressed]：过渡那一两百毫秒临时关掉缓冲指示（见 [suppressBufferingUi]）。
     * [retiringVideo] / [retiringHttp]：旧一集的视频与传输**不当场关**——旧源这时可能还在收尾，
     * 当场关掉会让它读到一个已关闭的视频对象而报错；等新源 READY 再收（见 [retirePreviousEpisode]）。
     */
    internal var bufferingSuppressed=false
    internal var retiringVideo: RemoteVideo?=null
    internal var retiringHttp: okhttp3.OkHttpClient?=null
    internal lateinit var artwork: ArtworkLoader
    internal data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    /**
     * 栅格里的一行：行容器 + 它这一行里固定 [GRID_COLUMNS] 张卡片。
     *
     * 卡片数与行容器一一对应，因此「换一行数据」就是给这几张卡重新 [bindGridCard]，
     * View 本身（以及它们身上的监听器、Drawable）全部复用，不再随滚动 / 追加新建。
     * [index] 是这一行在整份列表里的行号，窗口滑动时它跟着变。
     */
    internal class GridRow(val line: LinearLayout) {
        var index=0
        val cards=mutableListOf<PosterCard>()
    }
    internal var deferredWork: DeferredUi?=null
    internal var initialCatalogPending=true
    internal var restoreStoppedPage=false
    internal var pendingDetail: Series?=null
    /** 打开剧集时即时查到的云端进度（见 [openDetail]），比 accountData 里那份新。 */
    internal var resumeCloud: AccountHistory?=null
    internal var detailAutoplay=true
    internal var libraryLoadError: Throwable?=null
    internal var workScope: RequestScope?=null
    internal var workJob: java.util.concurrent.Future<*>?=null
    internal var playerListener: Player.Listener?=null
    internal lateinit var playbackTitle: TextView
    internal var playerClock: TextView?=null
    /** 顶部标题条（剧名 · 第几集 + 时钟）：播放时不打扰，只在唤出播放界面时露出来。 */
    internal var playbackTopBar: View?=null
    internal val clockFormat=java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault())
    internal var directPlayback=false
    /**
     * 本机记录集合页（收藏 / 历史 / 稍后观看）。
     *
     * 「热门集合页」整个去掉了：首页四个「热播…」剧架的行末「查看全部」直接进**对应的榜单页**
     * （见 [rankBoard]），与排行榜页读的是同一份数据，不再有第二套远端列表。
     */
    internal var collection: String?=null
    /**
     * 首页「再按一次退出」第一次按下的时刻（`SystemClock.elapsedRealtime()`；0 = 没按过）。
     *
     * 只记时刻、不记布尔：第二次按下是否算数由「距上一次是否超过 [EXIT_CONFIRM_WINDOW_MS]」
     * 判定，因此不需要额外的定时器去复位——超时之后的下一按自然就是新的第一次。
     */
    internal var exitArmedAt=0L
    /**
     * 「我的」页当前看的那一栏（[ACCOUNT_TABS] 的 key）。
     *
     * 默认是**第一栏「历史」**（与芯片顺序一致）：进这一页时用户最常看的是「我看到哪了」，
     * 而收藏是主动行为、频次低。默认值与芯片顺序不一致时，进页面会看到
     * 「焦点在第一枚芯片上、内容却是第二栏的」这种错位。
     */
    internal var accountTab="history"
    internal val pageSize=20
    internal var catalogScroll: ScrollView?=null
    internal var catalogBody: LinearLayout?=null
    internal var homePosition: HomeScreen.Position?=null
    internal var catalogPageKey=""
    /**
     * 页面内的一行提示（替代 Toast）。
     *
     * 应用里不再弹系统 Toast：它是独立于界面的一层浮窗，样式、字号、焦点行为都不受本应用控制，
     * 电视上还会盖在画面正中。需要「刚刚发生了什么」这类反馈时，改成在列表上方显示一行淡字
     * （[catalogHint]），随下一次翻页 / 刷新自动消失；不需要额外说明的（收藏、切主题、删除记录等）
     * 直接靠界面本身的变化表达。
     */
    /**
     * 账号侧的缓存：资料 + 收藏 / 历史 / 点赞 / 预约。
     *
     * null 表示「还没拉过」——个人信息页据此显示「正在读取…」并触发一次 [loadAccount]。
     * 它是内存缓存：账号数据每次进这一页都可以重取，不值得落盘（也可能过期）。
     */
    internal var accountData: AccountData?=null
    /** 文件选择器回传的 Uri；等 onResume 收尾之后再读（见 [pickAccountFile]）。 */
    internal var pendingAccountUri: android.net.Uri?=null
    internal var accountError: Throwable?=null
    internal var accountLoading=false
    /** 账号拉取的序号：只有最后一次请求的回调会落地（见 [loadAccount]）。 */
    internal var accountToken=0
    /** 设备注册是否在途：避免每次进前台都重复注册（见 [ensureDeviceRegistered]）。 */
    internal var deviceRegistering=false
    /** 注册的互斥锁：启动时那次异步注册与播放前那一次同步注册可能同时进来，只发一次请求。 */
    internal val deviceRegisterLock=Any()
    internal var catalogHint: String?=null
    internal var catalogHintView: TextView?=null
    internal val hideHint=Runnable { catalogHint=null; catalogHintView?.visibility=View.GONE }
    internal val scrollPositions=mutableMapOf<String,Int>()

    /** 记住一页列表的滚动位置；超出 [SCROLL_MEMORY] 条就丢掉最早的一条。 */
    internal fun rememberScroll(key: String,value: Int) {
        if(key.isBlank()) return
        if(key !in scrollPositions && scrollPositions.size>=SCROLL_MEMORY) scrollPositions.keys.firstOrNull()?.let(scrollPositions::remove)
        scrollPositions[key]=value
    }

    // —— 页头区域随列表滚动收起 ——
    /** 页头区域里的每一行：工具条、搜索行、榜单标题行、集合页标题行。 */
    internal val topArea=mutableListOf<View>()
    /** 每行展开时的高度（像素），收起动画的起点、展开动画的终点。 */
    internal val topAreaHeights=mutableMapOf<View,Int>()
    /** 每行展开时应该还原的 LayoutParams 高度（工具条是固定 TOOLBAR_HEIGHT，其余是 WRAP_CONTENT）。 */
    internal val topAreaRestore=mutableMapOf<View,Int>()
    internal val topAreaAnimators=mutableMapOf<View,android.animation.ValueAnimator>()
    internal var topAreaHidden=false
    /**
     * 「加载更多」是同一页的原地重建：这一次重建要保留页头当前的收 / 展状态，
     * 否则每追加一页页头都会弹回来一次。切换标签 / 分类这类换页仍然从展开态开始。
     */
    internal var keepTopAreaState=false
    internal var listScrollY=0
    internal var topAreaSettleUntil=0L

    /** 把一行页头登记进「随滚动收起」的区域。 */
    internal fun registerTopAreaRow(row: View) {
        topArea+=row
        val restore=row.layoutParams?.height ?: LinearLayout.LayoutParams.WRAP_CONTENT
        topAreaRestore[row]=restore
        if(topAreaHidden) {
            // 页头正收着，这一行也必须立刻收起来：先量出展开高度当基准，再按收起状态摆好，
            // 中间不留一帧展开态，否则重建时页头会闪一下。
            expandedHeightOf(row)
            applyTopAreaState(row,true)
        } else {
            // 布局完成后再量一次真实高度：收起动画的起点、展开动画的终点都按它算。
            row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
        }
    }

    /**
     * 列表滚动 → 页头收展。
     *
     * 收起用高度动画而不是 translationY：收掉之后列表真的多占这一条高度，顶部不会留一条空白，
     * 也不会出现「按钮浮在列表上面」的叠层。只有列表回到最上边、或者焦点回到页头里的控件时才展开：
     * 列表中间向上滚只保持当前状态，不会半路把页头弹回来。收起 / 展开之后有一小段静默期，
     * 避开视口变化引起的 scrollY 回夹，否则会在底部来回抖。
     */
    internal fun onListScroll(y: Int,force: Boolean=false) {
        // 静默期里只更新位置、不改收展状态：这期间的滚动是位置恢复 / 内容夹取，不是用户滚的。
        if(!force && android.os.SystemClock.elapsedRealtime()<topAreaSettleUntil) { listScrollY=y; return }
        listScrollY=y
        setTopAreaHidden(headerShouldHide(y),animate=!force)
        // 手指 / 遥控滚到离底部还有小半屏时就先把下一页取回来：
        // 真滚到底的时候内容已经在手上，接上是瞬间的，用户看不到「正在加载」。
        if(gridAtBottom(dp(240))) prefetchNextPage()
    }

    /**
     * 把页头的收 / 展状态按**当前位置与焦点**重算一次。
     *
     * 页头状态是「位置 + 焦点」的纯函数（见 [onListScroll]）：任何可能改变这两者的事件
     * 之后都该重算一次，否则状态会与事实脱节——**焦点移动**就是最容易被漏掉的那一类：
     * 焦点从第二行回到第一行时列表被 `reveal` 滚回顶部，那次滚动常常落在静默期或
     * `restoring` 里被吞掉，于是「位置已经回到顶部、页头却还收着」或者反过来。
     * 用户读到的就是「按上键页头反而隐藏」这种**状态反过来**的现象。
     */
    internal fun syncHeaderState() {
        if(screen!="catalog" || topArea.isEmpty()) return
        val y=homeScreen?.scrollY() ?: catalogScroll?.scrollY ?: return
        listScrollY=y
        setTopAreaHidden(headerShouldHide(y),animate=false)
    }

    /**
     * 页头该不该收起——**位置与焦点的纯函数**。
     *
     * 两条判据任一成立就收起：
     *
     * 1. **列表滚过顶部的余量**（`y > HEADER_SHOW_THRESHOLD`）：向下浏览时把页头让开，
     *    这是它本来的职责（滚回最上边就展开）；
     * 2. **焦点已经离开第一栏**（`focusedRowIndex() >= 1`）：首页的栏高是按「一屏放得下一栏」
     *    算的，焦点走到第二栏时往往**位置还是 0**（第二栏的下半截本来就在屏幕上），
     *    只看位置会判成「该展开」，而用户看到的是「焦点明明已经往下走了、页头却还占着地方」。
     *
     * 例外：焦点在页头里时**永远展开**——收着的话那一行高度是 0，焦点会停在一个看不见的控件上。
     *
     * 早先这里用「比上一次大就是向下滚」这种**增量**启发式，它依赖基线 [listScrollY] 正确；
     * 而基线在每次重建时都会被重置（[renderHome] 写死 0、栅格路径取新建 ScrollView 的 0），
     * 恢复位置之后基线与事实对不上，判据就给出与位置无关的结果——表现出来就是
     * 「焦点在第二行、页头却展开；按上键回到第一行、页头反而收起」这种**状态反过来**的现象。
     * 纯函数判据不依赖任何基线，任何时刻算出来的都与事实一致。
     */
    internal fun headerShouldHide(y: Int): Boolean {
        if(topAreaHasFocus()) return false
        if(y>dp(HEADER_SHOW_THRESHOLD)) return true
        return focusedContentRow()>=1
    }

    /**
     * 焦点当前落在内容区的第几行（从 0 起）；焦点不在内容上时返回 -1。
     *
     * 两种内容分别算：首页是剧架（[HomeScreen.focusedRowIndex]），
     * 其余页面是卡片栅格（[gridFocusIndex] 除以列数）。两者都按「第 1 行是页头正下方那一行」算。
     */
    private fun focusedContentRow(): Int {
        homeScreen?.let { home -> return home.focusedRowIndex() }
        val index=gridFocusIndex() ?: return -1
        return index/GRID_COLUMNS
    }

    /** 列表是不是已经滚到距底部 [slack] 像素以内。没有栅格（首页剧架、设置页）时永远为 false。 */
    internal fun gridAtBottom(slack: Int): Boolean {
        val scroll=gridScroll ?: return false
        val list=gridList ?: return false
        if(scroll.height<=0 || list.height<=0) return false
        return scroll.scrollY+scroll.height+slack>=list.height
    }

    private fun topAreaHasFocus()=topArea.any { it.findFocus()!=null }

    /**
     * 一行页头展开时的高度。工具条这类固定高度的行直接用 LayoutParams，
     * 其余（搜索行、榜单标题行、集合页标题行）按页面栅格宽度量一次自然高度，不用等布局。
     */
    private fun expandedHeightOf(row: View): Int {
        val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
        topAreaHeights[row]?.takeIf { it>0 }?.let { return it }
        if(restore>0) { topAreaHeights[row]=restore; return restore }
        // 收起状态不影响这次测量：量的是内容在页面栅格宽度下的自然高度。
        val width=if(row.width>0) row.width
            else (resources.displayMetrics.widthPixels-2*(pageInset()-pageBleed())).coerceAtLeast(1)
        row.measure(
            View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
        )
        val measured=row.measuredHeight
        if(measured>0) topAreaHeights[row]=measured
        // 量不出来（内容还没建好）就退回当前高度：这一行不做动画、直接到位，不会朝 0 缩。
        return measured.takeIf { it>0 } ?: row.height
    }

    /** 把一行页头直接摆到目标状态（不排队动画）：收起 = 高度归零 + 淡出 + 打开裁剪；展开反之。 */
    private fun applyTopAreaState(row: View,hide: Boolean) {
        (row as? android.view.ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
        row.animate().cancel()
        row.alpha=if(hide) 0f else 1f
        val restore=topAreaRestore[row] ?: LinearLayout.LayoutParams.WRAP_CONTENT
        row.layoutParams=row.layoutParams.apply { height=if(hide) 0 else restore }
    }

    internal fun setTopAreaHidden(hide: Boolean,animate: Boolean=true) {
        if(topAreaHidden==hide || topArea.isEmpty()) return
        topAreaHidden=hide
        // [animate] 为 false 时直接到位：用于「按当前滚动位置把状态摆正」那一次
        // （进页面 / 恢复位置之后），那时做动画反而会让页头闪一下。
        val span=if(animate && TvStyle.animate) TvStyle.DURATION else 0L
        topAreaSettleUntil=android.os.SystemClock.elapsedRealtime()+span+120
        topArea.forEach { row ->
            val from=row.height
            val to=if(hide) 0 else expandedHeightOf(row)
            // 先摘掉再取消：被取消的那条动画结束回调不会再回头改这一行的高度。
            topAreaAnimators.remove(row)?.cancel()
            row.animate().cancel()
            row.animate().alpha(if(hide) 0f else 1f).setDuration(span).start()
            // 收起过程中不再让子控件画到行外，展开时立刻恢复（焦点描边要继续画得出来）。
            (row as? android.view.ViewGroup)?.let { group -> group.clipChildren=hide; group.clipToPadding=hide }
            // 低内存设备（TvStyle.animate=false）直接到位，不排队动画；没有高度差时也一样。
            if(span==0L || from==to) { settleTopAreaRow(row,hide); return@forEach }
            topAreaAnimators[row]=android.animation.ValueAnimator.ofInt(from,to).apply {
                duration=span
                interpolator=android.view.animation.DecelerateInterpolator()
                addUpdateListener { animation ->
                    row.layoutParams=row.layoutParams.apply { height=animation.animatedValue as Int }
                }
                addListener(object: android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        // 摘掉自己这一步必须是原子的：这条回调也可能由 cancel() 同步触发。
                        if(topAreaAnimators.remove(row)===this@apply) settleTopAreaRow(row,hide)
                    }
                })
                start()
            }
        }
    }

    /** 动画结束：收起的行高度归零，展开的行还原成登记时的 LayoutParams 高度。 */
    private fun settleTopAreaRow(row: View,hide: Boolean) {
        applyTopAreaState(row,hide)
        // 展开到位后重新量一次真实高度，作为下一次收起 / 展开的基准。
        if(!hide) row.post { if(topArea.contains(row) && row.height>0) topAreaHeights[row]=row.height }
    }
    internal var prefetchAttemptAt=0L
    internal var prefetchAttempts=0
    internal var homeScreen: HomeScreen?=null
    internal var homeFromCache=false

    internal var homeUpdateCount=0
    internal var launchStarted=android.os.SystemClock.elapsedRealtime()
    internal val activityStarted=launchStarted
    internal var homeContentLogged=false
    internal lateinit var library: Library
    internal lateinit var tvTools: TvTools
    internal lateinit var mediaSession: TvMediaSession
    /** 应用内更新：前台空闲时检查新版，下载校验后交给系统安装器。 */
    internal lateinit var appUpdater: AppUpdater
    internal val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    internal var sleepStopped=false
    internal var playerView: PlayerView?=null
    /** 播放画面上的弹幕层；数据来自原 App 的评论接口（见 ContentRepository.danmaku）。 */
    internal var danmakuView: DanmakuView?=null
    /** 已经拿到弹幕的那一集：换清晰度、播放重试这类「同一集重进」不重复拉一遍。 */
    internal var danmakuFor=""
    internal var danmakuLoaded=false
    /** 这一集取到多少条弹幕；-1 表示还没取回来。播放界面的状态行会显示它，便于确认弹幕到底有没有到。 */
    internal var danmakuCount=-1
    internal lateinit var favoriteMonitor: FavoriteMonitor
    /**
     * 窗口化之后**不再**按剧集 id 记住「收藏状态那一行文字控件」。
     *
     * 卡片会被滚出窗口、回收、再拿去显示别的剧集，按 id 存下来的 TextView 引用会指到
     * 已经换了内容的控件上。收藏标签改成「重绑当前窗口那几行」（见 [rebindGridRows]），
     * 代价只有一屏的卡片数。
     */
    internal var favoriteStatus: TextView?=null
    internal var episodePanel: EpisodePanel?=null
    internal val recovery=RecoveryBudget()
    internal var retryPending=false
    internal var autoRecovery=false
    internal var startPosition=0L
    internal var retryPosition=0L
    internal var requestedAutoplay=true
    internal var retryAutoplay=true
    internal var foreground=false
    internal lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    internal val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HGTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    internal lateinit var root: FrameLayout
    internal var generation=0
    internal var screen="catalog"
    internal var tab=0
    internal var page=1
    internal var query=""
    internal var catalog=emptyList<Series>()
    internal var hasMore=false
    internal var catalogFocus=""
    internal data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    internal var ranking: ComicRanking?=null
    /**
     * 排行榜页当前看的是哪一个榜单。首页四个剧架的「查看全部」也会改它，
     * 因此「从首页进榜单」与「在榜单页里切换」是同一条路径、同一份数据。
     */
    internal var rankBoard=RankBoard.COMIC
    /** 首页四个剧架的内容（榜单第 1 页），按榜单分别存。 */
    internal var homeFeed=mutableMapOf<RankBoard,CatalogPage>()
    /**
     * 这一页的内容落地后，焦点是不是要落到第一张卡上。
     *
     * **不要靠「现在焦点在哪」去猜**：进榜单页 / 切榜单 / 切分类时，中间那段「正在加载…」
     * 曾经把焦点临时交给页头的控件，落地时再看「页头有没有焦点」就会把焦点又交回页头——
     * 表现出来就是「进榜单、切榜单之后焦点停在榜单按钮上」。因此改成显式的意图：
     * 换了一份列表（换榜单 / 换分类 / 从首页剧架进榜单）就置 true，落地时落到第一张卡并清掉。
     */
    internal var focusFirstCardOnLanding=false
    /**
     * 换榜单 / 换分类之后，焦点要留在**用户刚点的那一枚**芯片上。
     *
     * 芯片在页面重建时是新建的，旧引用已经离树（对它 requestFocus 一定失败），
     * 因此这里记的是 key，重建后由 [chipControl] 查回新的那一枚。
     * 取值 `board:<storedValue>` / `group:<path>` / `tag:<path>`；为空表示这次换列表不是「点了芯片」。
     * 内容落地（或加载失败）时消费掉。
     */
    internal var chipFocusKey: String?=null
    /** 剧架 key → 榜单（[HomeLayout.board]）：首页四个「热播…」栏目与排行榜页共用同一个映射。 */
    internal var rankSubtitle: TextView?=null
    internal var moreButton: View?=null
    /** 向下追加一页后焦点要落到的位置：新卡片 id、底部按钮（load:more）或 null（不指定）。 */
    internal var moreFocus: String?=null

    // —— 向下追加（无感加载）——
    /**
     * 当前栅格列表的活引用。
     *
     * 「展示更多」过去是 `page++` 之后整页重建：页头、分类开关、每一张卡都重画一遍，
     * 再靠记下来的 scrollY 把位置恢复回去。两次整页重建之间位置一夹不准就跳回顶部，
     * 焦点又会在恢复位置和「新卡片」之间来回抢，表现出来就是加载时抖、加载完乱。
     * 现在追加只在这张列表的末尾插新行：视口上方的像素一个都不动，
     * ScrollView 不需要任何位置恢复，也就没有「恢复得对不对」这回事。
     */
    internal var gridList: LinearLayout?=null
    internal var gridScroll: ScrollView?=null
    /** 底部那一行（进度文字 + 「展示更多」），追加时先摘下来再放回去，始终待在最后。 */
    internal var gridPaging: LinearLayout?=null
    internal var moreCount: TextView?=null
    /** 底部那一行当前是不是「还有下一页」的形态；只有它翻转时才需要重建这一行。 */
    internal var pagingHasMore=false
    /**
     * 栅格是**固定窗口**：只物化视口附近那几行，行与卡片都复用。
     *
     * 这是「加载几百个卡片之后越来越卡」的根治手段。窗口化之前是
     * `ScrollView + LinearLayout` 一行一个 View 全量常驻：600 张卡就是 600 行、
     * 每行 6 张卡、每张卡 11 个 View（约 6600 个 View），一次都不回收。
     * 于是每张卡片的成本是**永久的**：内存只增不减、每次追加 / 每次焦点移动都要在
     * 全部卡片上过一遍（`wireGridFocus` 重写全部焦点链、`gridCards()` 全量 flatMap），
     * 堆上活对象越多 GC 也越贵。加载越多越卡就是这么来的。
     *
     * 现在列表高度由两个 Space 撑出来（纯算术，不需要测量），中间只放
     * `[gridWindowStart, gridWindowStart + gridRows.size)` 这几行。因此：
     * View 数、每次追加的工作量、每次滚动的重排量都与「已加载多少」无关，只与屏幕高度有关。
     */
    internal var gridWindow: LinearLayout?=null
    internal var gridTopSpacer: View?=null
    internal var gridBottomSpacer: View?=null
    /** 当前显示的全部条目（窗口化之后不再是一行一个 View）。 */
    internal var gridItems: List<Series> = emptyList()
    /** 一行的像素高度（含行间距），首帧量一次；两个 spacer 的高度按它算。 */
    internal var gridRowStride=0
    /** 当前物化的第一行在整份列表里的行号；[gridRows] 与它连续。 */
    internal var gridWindowStart=-1
    /**
     * 正在同步窗口。
     *
     * [syncGridWindow] 会被三条路径重入：滚动回调、[focusGridIndex] 里的滚动 + 自己的同步、
     * 以及 `requestFocus` 经 `ScrollView.requestChildFocus → scrollTo` 再回到滚动回调。
     * 内层调用如果照常挪窗口，就可能把「正要获得焦点的那一行」回收掉，焦点随即落空。
     * 用这把标志把内层调用挡掉：外层那次算出来的窗口已经包含了焦点行。
     */
    internal var gridSyncing=false
    /** 当前物化的行，按屏幕顺序；焦点链只在这几行之间维护。 */
    internal val gridRows=mutableListOf<GridRow>()
    /** 回收的行：滚出窗口的行进这里，滚进来的行优先从这里取，不再新建 View。 */
    internal val gridRowPool=mutableListOf<GridRow>()
    internal var gridCardWidth=0
    internal var gridGap=0
    internal var gridPosterHeight=0
    /** 列表重建期间产生的滚动（位置恢复、焦点回带、内容夹取）都不算用户滚动。 */
    internal var gridRestoring=true
    /** 下一页请求在途：静默预取与用户按下共用这一把锁，同一页不会请求两次。 */
    internal var moreInFlight=false
    internal var moreInFlightPage=0
    /** 这次在途的请求是用户按出来的（底部显示「正在加载更多…」），还是静默预取（不打扰）。 */
    internal var moreVisible=false
    /** 在途的请求落地之后要不要把焦点交给新行的第一张卡（静默预取途中被用户按下时也会打开）。 */
    internal var moreFocusWanted=false
    /** 预取失败：底部改成重试文案，不再自动重试，免得断网时反复打接口。 */
    internal var moreFailed=false
    internal var gridPrefetchJob: java.util.concurrent.Future<*>?=null
    internal val tabState=mutableMapOf<Int,CatalogState>()
    internal val tabFocus=mutableMapOf<Int,String>()
    /**
     * 本次运行里已经进过哪些标签页。
     *
     * 用来判断「首次进入」：分类页与榜单页的**首次**落点是页头那枚芯片
     * （分类页是二级分类的「全部」、榜单页是当前榜单「漫剧」），而不是内容第一张卡——
     * 用户先要在这两页的页头挑一个，再往下看内容（见 [switchTab]）。
     * 从首页剧架进榜单、点芯片换分类也算「进过」（见 [switchRankBoard] / [selectCategory]），
     * 因此第二次进同一页时走原来的落点（内容第一张卡）。
     */
    internal val tabVisited=mutableSetOf<Int>()
    internal val nav=mutableListOf<View>()
    /** 排行榜页的四个榜单切换芯片（漫剧 / 短剧 / 真人 / AI）。 */
    internal val boardButtons=mutableMapOf<RankBoard,View>()
    /** 「我的」页的四个栏目切换芯片（收藏 / 历史 / 点赞 / 预约）。 */
    internal val accountButtons=mutableMapOf<String,View>()
    /**
     * 「我的」页那一行身份控件（头像 / 昵称 / 重新导入 / 清除 / 重新加载）。
     *
     * 它在导航栏下面、内容区上面，因此焦点链是「芯片 ↓ 这一行 ↓ 第一张卡」，
     * 而不是直接跳到内容——方向上是连续的（见 [wireGridFocus]）。
     */
    internal var profileHeadRow: View?=null
    /**
     * 分类页当前选中的小类（`CategoryCatalog` 里的路径，例如 `comic-drama/fantasy`）。
     * 默认是第一组的「全部」；选择记在本机，下次进分类页还是这一条。
     */
    internal var categoryPath=CategoryCatalog.defaultTag.path
    /**
     * 分类页的大类与小类芯片。
     *
     * 大类一行（漫剧 / 真人 / AI剧）、小类一行（全部 + 该大类下的小类）。
     * 两行都是互斥选择，点了就重新取第 1 页（分类页是「往下接」的长列表）。
     */
    internal val categoryGroupButtons=mutableMapOf<CategoryGroup,View>()
    internal val categoryTagButtons=mutableMapOf<String,View>()
    internal var searchInput: View?=null
    internal var searchButton: View?=null
    internal val searchActions=mutableListOf<View>()
    // —— 搜索提示词（联想词）——
    /**
     * 提示词那一行（横向可滚的芯片行）与它里面的按钮。
     *
     * 输入框每变一次就往后台丢一次请求（[SUGGEST_DEBOUNCE] 去抖），结果回来时
     * **只重建这一行**，不整页重画——否则每敲一个字页面都要闪一下。
     */
    internal var suggestRow: HorizontalScrollView?=null
    internal var suggestLine: LinearLayout?=null
    internal val suggestButtons=mutableListOf<View>()
    /** 这一行的请求令牌：晚回来的旧结果直接丢掉，不会盖住新关键词的提示词。 */
    internal val suggestToken=java.util.concurrent.atomic.AtomicLong(0)
    internal var suggestJob: java.util.concurrent.Future<*>?=null
    internal val suggestRun=java.util.concurrent.atomic.AtomicReference<Runnable?>(null)
    internal var historyManage: View?=null
    internal var detail: Detail?=null
    internal var episodeIndex=0
    internal var synopsisExpanded=false
    /** 详情页是否正在看「相关剧集」：展开时海报缩到与「开始观看」底边平齐，下方的选集换成相关剧集。 */
    internal var relatedOpen=false
    /**
     * 相关剧集的取数状态（后台预取，用户无感）。
     *
     * 流程：**详情一取回来就先渲染内容**，同时把相关剧集丢到后台去查（[prefetchRelated]）。
     * 用户点「相关剧集」时不再发新请求——取好了直接铺，还在途中就等它落地
     * （那点时间差通常察觉不到）。相关剧集现在是 App 详情页那一个「相关作品」接口
     * （`GET /reading/bookapi/plan/v:version/`，见 [ContentRepository.related]），
     * 一次请求就带回全部条目，整页的可见时间不受影响。
     */
    internal var relatedWorks: RelatedWorks?=null
    internal var relatedLoading=false
    internal var relatedFailed=false
    /** 相关剧集这条请求自己的令牌与句柄：它不能走 [work]（那条路会被本页重排取消掉）。 */
    internal val relatedToken=java.util.concurrent.atomic.AtomicLong(0)
    internal var relatedJob: java.util.concurrent.Future<*>?=null
    /** 正在预取 / 已预取的是哪一部剧，避免同一部剧重复发请求。 */
    internal var relatedFor=""
    internal var player: ExoPlayer?=null
    internal var video: RemoteVideo?=null
    internal var playbackReady=false
    internal var pausedForLifecycle=false
    internal lateinit var hud: LinearLayout
    internal lateinit var playbackText: TextView
    internal lateinit var progressBar: SeekIndicator
    internal lateinit var controls: LinearLayout
    internal var transportIcon: ImageView?=null
    internal var qualityOption: TextView?=null
    internal var speedOption: TextView?=null
    internal var frameOption: TextView?=null
    internal var danmakuOption: TextView?=null
    internal var sleepOption: TextView?=null
    internal var panel=false
    internal var playbackMenu: PlaybackMenu?=null
    /** 播放设置面板的外壳（固定屏幕下三分之一、负责裁剪与背板渐变）；摘浮层要摘这一层。 */
    internal var playbackMenuShell: View?=null
    /** 本次观看选中的定时项目下标（0=关闭），只用来决定菜单里的选中态。 */
    internal var sleepChoice=0
    internal val sleepLabels=listOf("关闭","15 分钟","30 分钟","60 分钟","90 分钟","本集播完","再 3 集","再 5 集")
    internal var speedDialog: TvDialog?=null
    internal var pendingSeek: Long?=null
    internal var playError=false
    private var lastSaved=0L
    internal var quality=""
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                if(sleepTimer.poll()) stopForSleep()
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                maybePrefetch()
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    internal val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    internal val seekRunnable=Runnable { pendingSeek?.let { player?.seekTo(it) }; pendingSeek=null }
    // ---- 播放过程中要按「同一个对象」重用的字段 ----
    // 这两枚 Runnable 会在 removeCallbacks 里按身份比对，必须是字段（扩展属性没有初始化器，
    // 写成 getter 会每次返回新对象、removeCallbacks 就失效了）。
    internal val restoreBuffering=Runnable { restoreBufferingUi() }
    internal val retireForced=Runnable { retirePreviousEpisode() }

    /**
     * 每 2 天清一次 cacheDir（封面缩略图等），后台线程删，不拖慢启动。
     * 收藏、进度、登录都在 SharedPreferences 里，不受影响；`updates/` 可能正在下载新版，跳过。
     */
    private fun cleanCachePeriodically() {
        val prefs=getSharedPreferences("cache-cleanup",MODE_PRIVATE)
        val now=System.currentTimeMillis()
        if(now-prefs.getLong("last",0) in 0 until 2*24*60*60*1000L) return
        prefs.edit().putLong("last",now).apply()
        clearCache()
    }
    /** 后台删 cacheDir（跳过 `updates/`），删完在主线程回调；设置页「清除缓存」也走这里。 */
    internal fun clearCache(done: () -> Unit = {}) {
        Thread {
            cacheDir.listFiles().orEmpty().filter { it.name!="updates" }.forEach { it.deleteRecursively() }
            android.util.Log.i("HGTV","cache cleaned")
            main.post { if(!isDestroyed) done() }
        }.start()
    }

    // ---- 生命周期与系统回调：扩展成员不能 override，只能留在 Activity 本体里 ----
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        // 主题要在建视图之前定下来：先落盘偏好 → 换色 → 丢掉柔光位图缓存（里面烤着上一套材质的投影）。
        // 窗口底色也跟着换，启动那一帧、页面切换的空档都不会闪出另一套材质。
        TvStyle.applyTheme(TvThemeStore.load(this))
        applyWindowBackground()
        // 低内存设备保留全部视觉层级，只关掉过渡动画、收紧柔光（投影更短更淡，生成也更快）。
        TvStyle.animate=getSystemService(android.app.ActivityManager::class.java)?.isLowRamDevice!=true
        Soft.quality=if(TvStyle.animate) 1f else .7f
        cleanCachePeriodically()
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this)
        root=FrameLayout(this).apply { setBackgroundColor(bg); clipChildren=false; clipToPadding=false }
        setContentView(root)
        // 焦点回到页头（品牌 / 分类开关 / 顶部入口）时立刻展开，
        // 否则收起的页头高度是 0，焦点会落在看不见的按钮上。
        root.viewTreeObserver.addOnGlobalFocusChangeListener { _,newFocus ->
            if(topAreaHidden && newFocus!=null && topArea.any { it===newFocus || it.findFocus()!=null }) setTopAreaHidden(false)
            // 焦点移动常会带动列表滚动（reveal 把焦点那一行滚进视口），而那次滚动可能落在
            // 静默期 / `restoring` 里不被上报。等一帧再按真实位置把页头状态摆正（见 [syncHeaderState]）。
            root.post { syncHeaderState() }
        }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        appUpdater=AppUpdater(this) {
            // 「空闲」= 在前台、不在播放界面、没有模态框。播放中与弹窗里绝不打扰。
            foreground && screen!="player" && !tvTools.showing
        }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }

    /** 文件选择器回传：只登记 Uri，真正的读取与导入交给 [onResume] 末尾的 [consumePendingAccountFile]。 */
    override fun onActivityResult(requestCode: Int,resultCode: Int,data: android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=REQUEST_ACCOUNT_FILE || resultCode!=RESULT_OK) return
        pendingAccountUri=data?.data
    }

    // ---- 导航与返回 ----
    internal fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepChoice=0; sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) showCatalog() else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { confirmExit(); return }
        when(screen) {
            "player" -> if(playbackMenu!=null || (hud.visibility==View.VISIBLE && !playError)) hidePlaybackOverlay() else returnToDetail()
            "detail" -> showCatalog()
            // 品牌不再是按钮，返回键就是唯一的「回主界面」入口：集合页回到它所在的标签（首页），
            // 搜索 / 排行榜 / 设置直接切回首页；已经在首页时再按就是**退出**（按两次，见 [confirmExit]）。
            //
            // 首页那一下不再先把焦点搬到顶部入口：那一步只是把焦点挪到「搜索」图标上，用户读到的
            // 是「按返回焦点乱跑」；页头收起时那一行的高度是 0，焦点请求根本没有可见的落点，
            // 连下一次「焦点在入口上」的判断都成立不了——表现出来就是「按多少次返回都没反应、
            // 也退不出去」。因此首页的返回一律直接走「再按一次退出」。
            else -> when {
                collection!=null -> { collection=null; catalogFocus=""; showCatalog() }
                tab!=TAB_HOME -> switchTab(TAB_HOME)
                else -> confirmExit()
            }
        }
    }
    /**
     * 主界面按返回的退出：**按两次**——第一次只弹一条提示，[EXIT_CONFIRM_WINDOW_MS] 之内
     * 再按一次才 [finish]。
     *
     * 不再弹确认框：电视上没有指针，确认框每次都要用户把焦点从「取消」挪到「退出」，
     * 比再按一下返回慢；而「再按一次退出」是 Android 上最熟的手势，第一次的提示已经把
     * 「要按两次」说清楚了。退出前也不需要再确认一遍数据：本机记录每次改动都即时落盘，
     * 播放进度在上屏 / 退出播放时已经写过（见 [saveProgress]）。
     */
    private fun confirmExit() {
        val now=android.os.SystemClock.elapsedRealtime()
        if(exitArmedAt!=0L && now-exitArmedAt<=EXIT_CONFIRM_WINDOW_MS) {
            exitArmedAt=0L
            finish()
            return
        }
        exitArmedAt=now
        tvTools.toast(EXIT_TOAST)
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            // 播放设置菜单打开时（[panel]）：方向键一律交给焦点系统，PlaybackMenu.wire() 已把四向
            // 锁在菜单内部，停在最后一行（定时）再按下键，焦点按 nextFocusDownId 落在自己身上、
            // 原地不动。这里**不能**再接管下键去弹选集：菜单是「上键」打开的浮层，用户在里面按到
            // 边缘只应该表示「到头了」，而不是顺带换一个界面（选集仍然可以由菜单关闭后的下键、
            // 底部居中的下拉箭头，以及返回键回到简介页进入——下键绑成什么由按键设置决定）。
            //
            // 六个方向键 / 菜单键 / 确定键在播放界面里**不做焦点移动**，一律按当前绑定去执行动作
            //（绑定与出厂值见 [com.hgtv.star.core.KeyMap]：上 / 菜单 = 播放设置、下 = 选集、
            // 左 = 快退、右 = 快进、确定 = 播放 / 暂停）。这一条以前是写死的 `when(key)`，
            // 现在按键设置页可以改，改完立刻生效、并随本机偏好与备份一起走。
            //
            // 播放界面上本来就没有可聚焦的按钮（控制条的图标与状态项都不抢焦点），
            // 因此这里不去「把键放给焦点系统」，直接把这一下消化掉：
            // 绑成「无」的键也只是什么都不做，不会让焦点跑到别处去。
            // 返回键在上面已经单独处理，任何绑定下都退得出播放界面。
            if(!panel) playerKeyAction(key)?.let { return dispatchPlayerKey(it,event) }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        appUpdater.resume()
        if(library.isLoaded) {
            if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    // 首页判空看的是四个剧架（[homeFeed]）：它的 catalog 本来就是空的，
                    // 用 catalog 判断会让每次回到前台都重打四个榜单。
                    else -> showCatalog(load=when {
                        // 集合页的内容是「本机 + 账号」合并出来的：回前台只重建（栅格分支里会
                        // 触发一次 loadAccount，账号那边新收藏 / 新看的剧随之出现）。
                        localCatalog() -> false
                        tab==TAB_HOME -> homeFeed.isEmpty()
                        tab==TAB_RANK -> catalog.isEmpty()
                        tab==TAB_CATEGORY -> catalog.isEmpty()
                        tab==TAB_SEARCH -> query.isNotBlank() && catalog.isEmpty()
                        else -> false
                    })
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
        } else libraryLoadError?.let(::showLibraryError)
        // 文件选择器回来的那一次：等上面这一轮页面重建（它会 cancelWork）走完，再读文件导入。
        if(pendingAccountUri!=null) main.post { consumePendingAccountFile() }
    }
    override fun onPause() {
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate(); appUpdater.pause()
        sleepTimer.cancel(); sleepChoice=0; favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // 柔光位图是可重建的纯缓存，内存吃紧时先丢它。
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) Soft.trim()
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); appUpdater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        io.shutdownNow(); prefetchWorker.shutdownNow(); danmakuWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hgtv-network-cleanup").start()
        super.onDestroy()
    }
}
