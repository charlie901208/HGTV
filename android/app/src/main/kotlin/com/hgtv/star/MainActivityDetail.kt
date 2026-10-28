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
import com.hgtv.star.player.EpisodeGrid
import com.hgtv.star.list.HomeScreen
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.roundCorners
import com.hgtv.star.list.showCatalog
import com.hgtv.star.tv.tvScrollHost

// 本文件是从 MainActivity.kt 拆出来的「详情页：简介、选集、相关推荐」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
    pendingDetail=series; detailAutoplay=autoplay
    invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null
    synopsisExpanded=false; relatedOpen=false
    relatedWorks=null; relatedLoading=false; relatedFailed=false; relatedFor=""
    relatedToken.incrementAndGet(); relatedJob?.cancel(true); relatedJob=null
    val body=base(series.title); message(body,"正在加载剧集…")
    work({
        // 云端进度与详情并行查：账号历史可能是几小时前拉的，手机上刚看的要即时问一次。查不到不影响打开。
        val cloud=java.util.concurrent.CompletableFuture.supplyAsync { runCatching { repository.accountProgress(series.id) }.getOrNull() }
        repository.detail(series.id) to runCatching { cloud.get(5,java.util.concurrent.TimeUnit.SECONDS) }.getOrNull()
    }, { (result,cloud) ->
        resumeCloud=cloud
        detail=result; library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
        // 内容已经在屏幕上（下面这一句就渲染），相关剧集丢后台去查：用户点开时多半已经就绪。
        prefetchRelated(series.id)
        val target=resumeTarget(series.id,result.episodes)
        episodeIndex=target.index
        if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
    }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
}
/**
 * 上次看到哪一集：本机进度与账号历史（手机上看的）取较新的一条。
 *
 * 两边是同一集时用本机那条（带播放位置）；账号历史只有集数，从该集开头播。
 */
internal fun MainActivity.resumeTarget(id: String,episodes: List<String>): ResumeTarget {
    val last=episodes.lastIndex
    val local=library.progress(id)
    // 集 id 对不上（平台换过 id）时退回存下来的集号，不再从第一集开始。
    val localIndex=local?.let { p -> episodes.indexOf(p.episodeId).takeIf { it>=0 } ?: p.episodeIndex.coerceIn(0,last) }
    val localTarget=when {
        local==null || localIndex==null -> ResumeTarget(0,0)
        local.completed -> ResumeTarget((localIndex+1).coerceAtMost(last),0)
        else -> ResumeTarget(localIndex,local.position.coerceAtLeast(0))
    }
    val remote=resumeCloud?.takeIf { it.series.id==id } ?: accountData?.history?.firstOrNull { it.series.id==id }
    val target=if(remote==null) localTarget else {
        val remoteIndex=episodes.indexOf(remote.episodeId).takeIf { it>=0 } ?: remote.episodeIndex.coerceIn(0,last)
        // ponytail: 平台的 last_watch_time 可能是秒，小于 1e12 的按秒换成毫秒再比。
        val remoteAt=remote.updatedAt.let { if(it in 1 until 1_000_000_000_000) it*1000 else it }
        // 云端没给时间时，谁看得更后面用谁。
        val newer=local==null || remoteAt>local.updatedAt || (remoteAt<=0 && remoteIndex>localTarget.index)
        if(newer && remoteIndex!=localIndex) ResumeTarget(remoteIndex,remote.position.coerceAtLeast(0)) else localTarget
    }
    android.util.Log.i("HGTV","resume series=$id local=${local?.episodeIndex}@${local?.updatedAt} cloud=${remote?.episodeIndex}@${remote?.updatedAt} -> episode=${target.index+1} pos=${target.position}")
    return target
}
/**
 * 详情页。
 *
 * [relatedOpen] 为真时走「相关剧集」布局：海报高度收到与「开始观看」底边平齐，
 * 下方的选集整块换成横向的相关剧集列表。[focusTarget] 用来在重排之后把焦点按回刚点的按钮。
 */
/**
 * 后台预取相关剧集（详情内容已经上屏之后才调用）。
 *
 * 剧名随请求一起传下去：筛选同系列靠它（见 [ContentRepository.related]），
 * 界面手里已经有 [detail]，不必让 core 再取一次详情页。
 *
 * 三个要点：
 *
 * 1. **不阻塞界面**：内容先渲染，这一条在 [io] 线程池上慢慢跑，用户看不到任何等待；
 * 2. **同一部剧不重复请求**：已经在跑或已经取到（[relatedFor]）就直接返回，
 *    因此用户点「相关剧集」时不会再发一次请求——取好了直接铺，还在途中就等它落地；
 * 3. **不走 [work]**：`work` 会取消上一次在途的请求（详情页重排、切集都会触发），
 *    而相关剧集是「同一部剧的附加内容」，被自己页面的重排取消掉就会永远停在加载态。
 *    这里单独持有 [relatedToken] + [relatedJob]：新剧作废旧剧，
 *    结果落地前再确认「还是同一部剧、还停在详情页」——中途换了剧或退出去，结果直接丢掉。
 */
internal fun MainActivity.prefetchRelated(id: String) {
    if(relatedFor==id && (relatedWorks!=null || relatedLoading)) return
    val token=relatedToken.incrementAndGet()
    relatedJob?.cancel(true)
    relatedFor=id; relatedLoading=true; relatedFailed=false
    relatedJob=io.submit {
        val result=runCatching { repository.related(id,detail?.series?.title.orEmpty()) }
        main.post {
            if(isDestroyed || relatedToken.get()!=token) return@post
            relatedLoading=false
            result.onSuccess { works -> relatedWorks=works }.onFailure { relatedFailed=true }
            // 用户此刻正好停在「相关剧集」这一栏里，就地铺上；否则什么也不用做。
            if(relatedOpen && screen=="detail" && detail?.series?.id==id) showDetail(false)
        }
    }
}
/**
 * 点「相关剧集」时调用：预取还没开始（或上一次失败了）才补一次请求。
 * 正常路径下这里什么都不做——请求早在详情上屏时就已经发出去了。
 */
internal fun MainActivity.ensureRelated(id: String) {
    if(relatedWorks!=null) return
    if(relatedLoading && relatedFor==id) return
    prefetchRelated(id)
}
internal fun MainActivity.showDetail(focusEpisode: Boolean, focusTarget: View?=null) {
    invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
    // 详情页不留页头：顶部是完整竖版海报 + 剧集信息（78dp 栅格内），
    // 下方选集网格整幅贴底，与播放器选集面板同一份实现。
    root.removeAllViews(); root.setBackgroundColor(bg)
    val container=column().apply { clipChildren=false; clipToPadding=false }
    root.addView(container,FrameLayout.LayoutParams(-1,-1))
    val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false; clipChildren=false; clipToPadding=false } // focus-bleed-exempt: 边界就是整屏宽，最近的焦点控件（操作按钮 / 选集外壳）离屏幕边缘 ≥71dp
    val content=column().apply { clipChildren=false; clipToPadding=false }
    scroll.addView(content); container.addView(tvScrollHost(scroll),lp(-1,0).apply { weight=1f })
    val body=column().apply {
        clipChildren=false; clipToPadding=false
        setPadding(pageInset(),dp(18),pageInset(),dp(14))
    }
    // 顶部：完整竖版海报 + 剧集信息左右分栏。
    // 封面按原始比例 FIT_CENTER 整张显示（接口封面约 7:10），不再铺满裁切；
    // 文字改到海报右侧的纯色页面上，因此也不需要压暗层或文字投影。
    // 海报高度吃掉「屏高 − 选集块 − 页面内边距」的剩余空间，整页因此铺满屏幕，
    // 底部不会留出一块空白；估算偏差由下面的弹簧 Space 吸收，内容过高时才滚动。
    val screenHeight=resources.displayMetrics.heightPixels
    // 选集网格的单元格高度含柔光带，因此整块比改版前高约 30dp；预留给它的空间跟着加。
    val episodesHeight=dp(206+2*TvStyle.SOFT_BAND+20)
    val posterHeight=(screenHeight-dp(32)-episodesHeight)
        .coerceIn(dp(240),(screenHeight*.72f).toInt())
    val posterWidth=(posterHeight*7f/10f).toInt()+2*dp(TvStyle.SOFT_BAND)
    val hero=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    // 海报卡座：一块凸起底板，海报按柔光带内缩嵌在里面，不再是「描边 + 通栏图」。
    val posterBand=dp(TvStyle.SOFT_BAND)
    val posterBox=FrameLayout(this).apply {
        background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_CARD,depth=1.15f)
        clipChildren=true; clipToPadding=true
        setPadding(posterBand,posterBand,posterBand,posterBand)
    }
    roundCorners(posterBox,TvStyle.RADIUS_CARD)
    val backdrop=ImageView(this).apply {
        scaleType=ImageView.ScaleType.FIT_CENTER
        importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    roundCorners(backdrop,TvStyle.RADIUS_POSTER)
    posterBox.addView(backdrop,FrameLayout.LayoutParams(-1,-1))
    loadCover(backdrop,data.series.cover)
    hero.addView(posterBox,lp(posterWidth,posterHeight).apply { rightMargin=dp(44) })
    val info=column().apply {
        clipChildren=false; clipToPadding=false
        setPadding(0,dp(4),0,0)
    }
    hero.addView(info,lp(0,-2).apply { weight=1f })
    info.addView(text(data.series.title,34f).apply {
        setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END
    })
    val meta=row().apply { setPadding(0,dp(14),0,dp(14)) }
    meta.addView(text("★",16f,TvStyle.tips))
    meta.addView(text("全 ${data.episodes.size} 集",16f,TvStyle.textDim).apply { setPadding(dp(8),0,dp(16),0) })
    meta.addView(text(data.series.tags,16f,TvStyle.textDim).apply {
        maxLines=1; ellipsize=TextUtils.TruncateAt.END
    })
    info.addView(meta)
    val description=data.series.description.ifBlank { "选择剧集开始观看" }
    val synopsis=text(description,16f,TvStyle.textDim).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
    info.addView(synopsis)
    // 按钮行四边各留 FOCUS_BLEED，放大后的焦点底色才不会在这一行里被切平；
    // 行本身用负外边距抵回去，按钮的位置与原来完全一致（仍从内容线开始）。
    val actions=row().apply {
        setPadding(dp(TvStyle.FOCUS_BLEED),dp(TvStyle.GAP_BLOCK),dp(TvStyle.FOCUS_BLEED),dp(TvStyle.FOCUS_BLEED))
    }
    // 四个入口（播放 / 收藏 / 完整简介 / 相关剧集）**等宽**：按钮文字长短不一
    // （「继续第 12 集」与「收藏」差一倍），按内容宽度排出来这一行会参差不齐。
    //
    // 用 weight=1 让父容器等分，**不要自己按屏宽算像素**：这一行在海报右侧的信息列里，
    // 可用宽度是「屏宽 − 两侧边距 − 海报宽 − 间距」，按整屏算会直接溢出屏幕（踩过一次）。
    // 文字放不下时 TvButton 自己省略号收尾。
    val actionGap=dp(TvStyle.GAP_INLINE)
    // 等宽之后每个按钮的可用宽度只有整行的四分之一，HERO 档的 36dp 内边距会把
    // 「继续第 12 集」挤成省略号，因此这一行单独把左右内边距收到 14dp（字号与高度不变）。
    val actionPad=TvStyle.GAP_INLINE+2
    info.addView(actions,lp(-1,-2).apply { leftMargin=-dp(TvStyle.FOCUS_BLEED); rightMargin=-dp(TvStyle.FOCUS_BLEED) })
    val progress=library.progress(data.series.id)
    val watched=library.watched(data.series.id)
    // 播放按钮的**文案固定为「播放」**，不随状态变（曾按状态显示「开始观看 / 继续第 N 集 / 重新观看」，
    // 用户要求统一成一个词）。续播目标仍然照算——`ResumePlayback.target` 处理了
    // 「上一集看完 → 从下一集开头」「看到一半 → 回到那一集的那个位置」两种情况，
    // 文案与动作因此仍然同源，只是不再把集数写在按钮上。
    val target=resumeTarget(data.series.id,data.episodes)
    // 这一行的三个按钮同档同高（56dp），不再出现「主按钮 56、旁边两个 48」的高低差；
    // 强调色仍然只给主操作，尺寸与语义分开管。
    // 播放按钮不再用 PRIMARY（常亮的亮面板）：简介页这一行是四个平级入口，
    // 只有一个亮着会让「收藏 / 完整简介 / 相关剧集」看起来像次要操作。
    // 四个按钮统一常规档 + 等宽，靠位置与文案区分主次。
    val play=addButton(actions,"播放",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
        if(watched) playEpisode(0,0) else playEpisode(target.index,target.position)
    }
    lateinit var favorite: TvButton
    favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
        favoriteMonitor.stop()
        val saved=library.toggle(data.series)
        syncFavorite(data.series,saved)
        if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
        favorite.text=if(saved) "已收藏" else "收藏"
    }
    lateinit var synopsisButton: TvButton
    synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
        synopsisExpanded=!synopsisExpanded
        synopsis.maxLines=if(synopsisExpanded) 20 else 2
        synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
        synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
    }
    // 「相关剧集」排在「完整简介」之后：点开时海报缩到与「开始观看」底边平齐，
    // 下方的选集整块换成横向的相关作品；再点一次收起，回到原样。
    lateinit var relatedButton: TvButton
    relatedButton=addButton(actions,if(relatedOpen) "收起相关" else "相关剧集",size=ButtonSize.HERO,weight=1f,pad=actionPad) {
        relatedOpen=!relatedOpen
        showDetail(false,relatedButton)
        if(relatedOpen) ensureRelated(data.series.id)
    }
    body.addView(hero,lp(-1,-2))
    content.addView(body,lp(-1,-2))
    // 详情页没有页头，上下移动只能靠按钮行与下方那一块（选集 / 相关剧集）互相指：
    // 按钮行里「相关剧集」与「完整简介」按下键进下方那一块，下方第一行的上键回到按钮行。
    relatedButton.nextFocusDownId=relatedButton.id
    // 弹簧：内容不足一屏时把选集推到屏幕底部，而不是在页面底部留一块空白。
    content.minimumHeight=screenHeight
    content.addView(Space(this),lp(-1,0).apply { weight=1f })
    // 下方这一整块：默认是选集网格，点开「相关剧集」之后换成横向的相关作品列表。
    // 两者互斥——相关剧集展开时不显示选集，收起时恢复选集。
    if(relatedOpen) {
        val works=relatedWorks
        if(relatedLoading || (works==null && !relatedFailed)) {
            content.addView(text("正在查找同系列…",16f,TvStyle.textDim).apply {
                setPadding(pageInset(),dp(28),pageInset(),dp(18))
            },lp(-1,-2))
        } else if(relatedFailed) {
            // 失败给一个可重试的入口，而不是静默空列表。
            val retry=row().apply { setPadding(pageInset(),dp(24),pageInset(),dp(18)) }
            val label=text("相关剧集加载失败",16f,TvStyle.textDim).apply { setPadding(0,0,dp(TvStyle.GAP_BLOCK),0) }
            retry.addView(label,lp(-2,-2))
            addButton(retry,"重试") { prefetchRelated(data.series.id) }
            content.addView(retry,lp(-1,-2))
        } else if(works==null || works.items.isEmpty()) {
            // 接口没给相关剧集（或这一部确实没有）：如实说明，不留一块空白。
            content.addView(text("这一部暂时没有相关剧集",16f,TvStyle.textDim).apply {
                setPadding(pageInset(),dp(28),pageInset(),dp(18))
            },lp(-1,-2))
        } else {
            val related=HomeScreen(this,posterBox,pageInset(),::loadCover,
                { item,_ -> openDetail(item) },{ item,anchor,shelf -> quickActions(item,anchor,shelf) },{})
            // 同系列各季排在前面并标注「第 N 季」（正在看的那一季写「正在看」），
            // 之后是官网的相关短剧（状态行用它自己的角标）。标题按有没有同系列区分。
            val seasonIds=works.seasons.map { it.id }.toSet()
            val title=if(works.seasons.size>1) "同系列 ${works.seasons.size} 季 · 相关剧集" else "相关剧集"
            related.renderCompact(listOf(HomeScreen.Shelf("related",title,
                works.items.map { item ->
                    val season=if(item.id in seasonIds) SeriesSeasons.season(item.title) else 0
                    // 「第 N 季」放在海报角标上（状态行那点字号读不出季数），同系列一眼可辨。
                    val badge=when {
                        item.id==data.series.id -> "正在看"
                        season>0 -> "第 $season 季"
                        else -> ""
                    }
                    HomeScreen.Entry(item,item.badge.ifBlank { "查看剧集" },badge=badge)
                })),"",false)
            val host=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
            host.addView(related,lp(-1,-2))
            content.addView(host,lp(-1,-2))
            related.post {
                related.firstCard()?.let { card ->
                    // 按钮行按下键落到相关作品的第一张卡；卡片按上键回到「相关剧集」按钮。
                    synopsisButton.nextFocusDownId=card.id
                    relatedButton.nextFocusDownId=card.id
                    card.nextFocusUpId=relatedButton.id
                    if(focusTarget==null) card.requestFocus()
                }
            }
        }
    } else {
        // 选集与播放界面共用同一个 EpisodeGrid：这里整幅贴在页面末尾（不加页面栅格内边距），
        // 标题行、集数行、单元格尺寸与播放器弹窗一致，翻页也是同一套四向逻辑。
        // 第一页第一行按上时网格没有上一页可翻，EpisodeGrid.turn() 返回 false 把按键交回焦点系统，
        // 焦点因此仍然能回到上面的「继续观看 / 收藏 / 完整简介」这一行（详情页不留页头，上面只有这一行）。
        val episodes=EpisodeGrid(this,data.episodes.size,episodeIndex,player=false,
            { index -> playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) })
        // 简介页的选集不套背板：它就是页面里的一块内容，底板会读成「一个可以按进去的大盒子」。
        // 播放界面里同一份网格仍然用浮层渐变（播放界面不套新拟态，见 EpisodeGrid 的注释）。
        // 选集整幅贴底，但左右要与页面内容线对齐：网格自己的横向内边距只留给播放界面
        // （那是整幅压屏的浮层），简介页用一个补回焦点余量的外壳把它托到内容线上，
        // 「选集」这一行与第一格于是和上面的海报、按钮同一条竖线。
        val episodeHost=row().apply { setPadding(pageInset()-dp(TvStyle.FOCUS_BLEED),0,pageInset()-dp(TvStyle.FOCUS_BLEED),0) }
        episodeHost.addView(episodes,lp(-1,-2))
        content.addView(episodeHost,lp(-1,-2))
        // 按钮行按下键进选集网格；网格第一行的上键回到「相关剧集」（网格自己会按需要交还按键）。
        val firstCell=episodes.firstCell()
        synopsisButton.nextFocusDownId=(firstCell?.id ?: synopsisButton.id)
        relatedButton.nextFocusDownId=(firstCell?.id ?: relatedButton.id)
        firstCell?.nextFocusUpId=relatedButton.id
        if(focusEpisode) episodes.focusCurrent() else play.requestFocus()
    }
    // 海报高度：相关剧集展开时与右边按钮行的底边平齐（= 信息区的高度），
    // 收起时按原来的算法铺满剩余屏高。信息区高度要等布局完成才量得准，因此在这里回填。
    // 宽度跟着高度按 7:10 一起缩，否则封面会在变矮的卡座里左右留出两条空白（FIT_CENTER 不会拉伸）。
    fun fitPoster(attempt: Int=0) {
        val target=if(relatedOpen) info.height else posterHeight
        // 首帧里信息区可能还没量完（height==0）：再等一帧，最多两次，避免海报停在错误高度上。
        if(target<=0 && attempt<2) { hero.post { fitPoster(attempt+1) }; return }
        if(target<=0 || target==posterBox.layoutParams.height) return
        val width=(target*7f/10f).toInt()+2*posterBand
        posterBox.layoutParams=(posterBox.layoutParams as LinearLayout.LayoutParams).apply {
            height=target; this.width=width
        }
        posterBox.requestLayout()
    }
    hero.post { fitPoster() }
    if(focusTarget!=null) focusTarget.post { focusTarget.requestFocus() }
}
/**
 * 取本集弹幕（原 App 播放时看到的那一份，见 [ContentRepository.danmaku]）。
 *
 * 只有播放画面上的弹幕层会用到它：取不到、这一集没有、平台不再返回，都只是「没有弹幕」，
 * 不弹提示、不影响播放。[danmakuToken] 每换一集自增一次，
 * 在途的窗口循环发现 token 变了就停手，旧剧的弹幕不会接到新一集上。
 */
