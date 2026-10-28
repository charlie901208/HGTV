// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.list

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
import com.hgtv.star.MainActivity.GridRow
import com.hgtv.star.tv.PosterCard
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.GRID_COLUMNS
import com.hgtv.star.GRID_WINDOW_MAX_ROWS
import com.hgtv.star.GRID_WINDOW_MIN_ROWS
import com.hgtv.star.LOAD_MORE
import com.hgtv.star.MAX_CATALOG_PAGE
import com.hgtv.star.MainActivity
import com.hgtv.star.TAB_CATEGORY
import com.hgtv.star.TAB_HOME
import com.hgtv.star.TAB_PROFILE
import com.hgtv.star.TAB_RANK
import com.hgtv.star.TAB_SEARCH
import com.hgtv.star.appendCatalog
import com.hgtv.star.autoAppendCatalog
import com.hgtv.star.button
import com.hgtv.star.dp
import com.hgtv.star.focusHead
import com.hgtv.star.formatTime
import com.hgtv.star.gapInto
import com.hgtv.star.headFocusTarget
import com.hgtv.star.loadCover
import com.hgtv.star.lp
import com.hgtv.star.navIcons
import com.hgtv.star.openDetail
import com.hgtv.star.pageInset
import com.hgtv.star.prefetchCover
import com.hgtv.star.profileHeadControl
import com.hgtv.star.profileHeadControls
import com.hgtv.star.quickActions
import com.hgtv.star.row
import com.hgtv.star.showHint
import com.hgtv.star.text

// 本文件：剧卡栅格的窗口化复用、分页追加、滚动与焦点。
//
// list/ 这个包就是「列表组件」这一块的全部代码，只有三件事：
//   ListPage.kt    页面骨架与唯一入口 [seriesList]（页头、空页、错误、跑数据）
//   SeriesGrid.kt  竖排六列栅格：窗口化、行复用、追加、焦点（本文件）
//   HomeScreen.kt  横排剧架：首页四栏、详情页相关推荐
// 卡片（tv/PosterCard）、点 / 长按动作（[openSeries] / [seriesMenu]）两边共用。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.moreLabel(): String = when {
    moreFailed -> "加载失败 · 重试"
    moreVisible && moreInFlight -> "正在加载更多…"
    else -> "展示更多 ↓"
}
/**
 * 「向下展示更多」。
 *
 * 有活着的栅格就只往列表末尾接（视口上方的像素一个不动），没有栅格（空页）才退回整页重建。
 */
internal fun MainActivity.loadMore() {
    if(!hasMore || !appendCatalog()) return
    if(gridList==null || gridItems.isEmpty()) {
        moreFocus=null; page++; catalogFocus=""
        // 追加是同一页的原地重建：页头保持当前的收 / 展状态，不会因为加载更多又弹回来。
        keepTopAreaState=true
        showCatalog(true)
        return
    }
    requestNextPage(userInitiated=true)
}
/**
 * 静默取下一页：焦点走到倒数两行、或者列表滚到接近底部时触发。
 *
 * 取回来直接接在列表末尾（在视口下方，用户看不见），全程不显示加载态——
 * 这就是「无感」的来源：请求发生在用户看到底部之前。
 */
internal fun MainActivity.prefetchNextPage() {
    // 换榜单 / 换分类的请求还在路上：屏上那份列表属于上一条选择，
    // 拿它去取「下一页」会把新列表的页码算乱（旧列表的追加会先落进 catalog）。
    if(chipFocusKey!=null) return
    // [moreFailed] 之后不再自动重试：断网时滚动事件会把预取变成对着接口猛敲，
    // 等用户自己按下「加载失败 · 重试」再打一次就够了。
    if(screen!="catalog" || !appendCatalog() || !hasMore || moreInFlight || moreFailed || gridList==null) return
    if(page+1>MAX_CATALOG_PAGE) return
    requestNextPage(userInitiated=false)
}
/**
 * 把取回来的一页接在列表末尾。
 *
 * 关键在「只动下面」：先把底部行摘下来，把新行插在它前面再放回去，
 * 视口上方的所有内容一个像素都没动，ScrollView 因此不需要任何位置恢复，
 * 也就不会出现「加载更多之后跳回顶部 / 跳到别处」。
 * [userInitiated] 为 false 表示这是后台预取落地，不抢焦点、不滚动。
 */
internal fun MainActivity.applyNextPage(next: Int,result: CatalogPage,userInitiated: Boolean) {
    if(!hasMore || !appendCatalog() || gridList==null) return
    val added=result.items.filterNot { item -> catalog.any { it.id==item.id } }
    catalog=catalog+added
    ranking=mergeRanking(ranking,result.ranking)
    hasMore=result.hasMore
    page=next
    val target=if(userInitiated) added.firstOrNull()?.id ?: LOAD_MORE else null
    if(userInitiated) catalogFocus=added.firstOrNull()?.id.orEmpty()
    appendGridItems(added,target)
    // 只有用户已经站在底部附近才继续往后取：否则一次预取会连锁把整份目录都拉下来。
    if(hasMore && nearGridEnd()) prefetchNextPage()
}
/**
 * 用户是不是已经站在列表底部附近：焦点在倒数两行之内，或者视口已经接近列表末尾。
 * 「继续往后预取」与「一次性把整份目录拉下来」的分界线就在这个判断上。
 */
internal fun MainActivity.nearGridEnd(): Boolean {
    if(gridAtBottom(dp(240))) return true
    val row=gridFocusIndex()?.let { it/GRID_COLUMNS } ?: return false
    return row>=gridRowCount()-2
}
/**
 * 去取第 `page+1` 页。
 *
 * [userInitiated] 为 true 时底部显示「正在加载更多…」，结果落地后焦点落到新行的第一张卡；
 * 静默预取只记在 [moreInFlight] 上，界面上什么都不变。
 * 两者共用同一把锁，所以同一页不会被请求两次；静默预取在途中被用户按下时，
 * 只是把 [moreFocusWanted] 打开，落地时照样把焦点交过去。
 */
internal fun MainActivity.requestNextPage(userInitiated: Boolean) {
    if(chipFocusKey!=null) return
    if(!hasMore || !appendCatalog() || gridList==null) return
    if(moreInFlight) {
        if(userInitiated) { moreVisible=true; moreFocusWanted=true; updateFooter() }
        return
    }
    val next=page+1
    if(next>MAX_CATALOG_PAGE) { hasMore=false; updateFooter(); return }
    moreInFlight=true; moreInFlightPage=next; moreVisible=userInitiated
    moreFocusWanted=userInitiated; moreFailed=false
    updateFooter()
    val ticket=generation; val board=rankBoard; val category=categoryPath
    val requestTab=tab; val scope=collection; val requestQuery=query
    gridPrefetchJob=io.submit {
        // 榜单、分类、搜索都走这条「往下接」的路：第 2 页起接着取同一份列表，
        // 页头与已显示的剧集都不动（搜索结果按同一关键词继续取）。
        val result=runCatching {
            when {
                requestTab==TAB_SEARCH && scope==null -> repository.search(requestQuery,next)
                requestTab==TAB_CATEGORY && scope==null -> repository.category(category,next)
                else -> repository.rank(next,board)
            }
        }.getOrNull()
        main.post {
            if(isDestroyed || generation!=ticket) return@post
            if(moreInFlightPage==next) { moreInFlight=false; moreVisible=false }
            val wantsFocus=moreFocusWanted; moreFocusWanted=false
            if(result==null) {
                if(wantsFocus) showHint("加载更多失败，请检查网络后重试")
                moreFailed=true; updateFooter()
                return@post
            }
            // 只有「还是原来那一页」才接得上；中途切了标签 / 榜单 / 集合，结果直接丢掉。
            if(scope==collection && requestTab==tab && requestQuery==query && rankBoard==board &&
                (requestTab!=TAB_CATEGORY || categoryPath==category) && screen=="catalog" && gridList!=null)
                applyNextPage(next,result,wantsFocus)
            else updateFooter()
        }
    }
}
/**
 * 把新取回的一页接在列表末尾。
 *
 * 窗口化之后这一步**不建任何 View**：只是把整份列表变长，再让两个 spacer 按新的行数
 * 重算高度。视口上方的像素一个不动，滚动位置也不需要（也不应该）做任何恢复。
 * 因此「加载更多」的代价是 O(1)，与已经加载了多少部剧无关。
 */
internal fun MainActivity.appendGridItems(added: List<Series>,focusTarget: String?) {
    if(gridList==null) return
    // 过滤规则必须与 seriesList 里算 displayed 的那一套完全一致，否则 gridItems 与
    // 实际显示的内容会对不上，按索引算出来的焦点落点就会错位。
    val visible=if(tab==TAB_HOME || tab==TAB_RANK || tab==TAB_CATEGORY) added.filterNot { it.id in library.hidden() } else added
    if(visible.isNotEmpty()) {
        // 追加只是把「整份列表」变长：**一个 View 都不用建**。
        // 底部那一行与两个 spacer 都按行数重算，视口上方的像素一个不动。
        gridItems=gridItems+visible
        syncGridWindow()
        // 已经物化的那几行必须**重新绑一次**。
        //
        // 一页 20 部按六列切开，最后一行只有 2 张、后面 4 个槽位是空槽（见 [bindGridRow]）；
        // 新的一页要填进这 4 个空槽。但追加时窗口范围没变，[syncGridWindow] 会走提前返回，
        // 那一行不会重绑——于是那 4 个槽位一直是空槽，用户看到的是
        // 「第 4 行只有 2 张，新内容直接从第 5 行开始」，中间像少了一段。
        // 重绑的代价只有当前物化的那几行（≤ 一屏 + 2 行），与已加载多少无关。
        rebindGridRows()
    }
    // hasMore 可能刚翻成 false（接上的是最后一页）：只有形态真的变了才重建底部这一行。
    // 没变就只改文案——重建会把焦点按着的「展示更多」按钮换掉，焦点会先掉一帧再回来。
    if(pagingHasMore!=hasMore) rebuildPagingRow() else updateFooter()
    wireGridFocus(null,moreButton?.takeIf { it.isFocusable })
    when {
        focusTarget==null -> Unit
        focusTarget==LOAD_MORE -> moreButton?.let { button -> button.requestFocus(); revealRow(gridRowCount()-1,true) }
        else -> gridItems.indexOfFirst { it.id==focusTarget }.takeIf { it>=0 }?.let { index -> focusGridIndex(index,true) }
    }
}
/** 底部一行：已显示进度 + 「展示更多」。hasMore 变成 false 时只留进度文字。 */
internal fun MainActivity.fillPagingRow(paging: LinearLayout) {
    moreButton=null; moreCount=null
    pagingHasMore=hasMore
    paging.addView(text(moreCountText(),14f,muted).apply { gravity=Gravity.CENTER },lp(dp(200),dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)))
    moreCount=paging.getChildAt(0) as? TextView
    // 搜索结果只要这一行进度文字：加载由滚动与焦点触发，不需要再按一个按钮。
    if(!hasMore || autoAppendCatalog()) return
    val more=button(moreLabel()) { loadMore() }.apply {
        tag=LOAD_MORE
        nextFocusLeftId=id; nextFocusRightId=id
        // 停在底部按钮上再按下键也是「继续往下看」，不用先按确认。
        setOnKeyListener { _,key,event ->
            if(key==KeyEvent.KEYCODE_DPAD_DOWN && event.action==KeyEvent.ACTION_UP) { loadMore(); true } else false
        }
    }
    gapInto(paging,more,lp(-2,-2))
    moreButton=more
    updateFooter()
}
/** 重建底部那一行（只有 hasMore 翻转时需要，其它情况走 [updateFooter] 就地改文案）。 */
internal fun MainActivity.rebuildPagingRow() {
    val paging=gridPaging ?: return
    val keepButtonFocus=moreButton!=null && moreButton!!.hasFocus()
    paging.removeAllViews()
    fillPagingRow(paging)
    if(keepButtonFocus && moreButton==null) gridRows.lastOrNull()?.cards?.firstOrNull()?.requestFocus()
}
/** 底部文案与按钮状态就地更新：不动子控件，焦点不会掉。 */
internal fun MainActivity.updateFooter() {
    moreCount?.text=moreCountText()
    val more=moreButton ?: return
    (more as? TvButton)?.setLabel(moreLabel())
    more.alpha=if(moreVisible && moreInFlight) TvStyle.DISABLED_ALPHA else 1f
}
/** 栅格里的全部卡片：只包含**当前物化的窗口**那几行（窗口外没有 View）。 */
internal fun MainActivity.gridCards(): List<View> = gridRows.flatMap { holder -> holder.cards }

/** 一行是不是整份列表的最后一行（追加之后会变，所以每次现算）。 */
internal fun MainActivity.isGridTail(holder: GridRow)=holder.index>=gridRowCount()-1

/** 整份列表的行数。 */
internal fun MainActivity.gridRowCount()=if(gridItems.isEmpty()) 0 else (gridItems.size+GRID_COLUMNS-1)/GRID_COLUMNS

/**
 * 一行的像素高度（含行间距）。
 *
 * 首帧还没量到时先按海报高度估一个：两个 spacer 必须在第一次布局之前就有高度，
 * 否则 ScrollView 会把恢复位置夹成 0（与「列表还没测量就 scrollTo」是同一个坑）。
 * 量到真实行高之后立刻修正一次，之后每帧都是纯算术。
 */
internal fun MainActivity.rowStride(): Int = if(gridRowStride>0) gridRowStride
    else (gridPosterHeight+dp(2*TvStyle.SOFT_BAND+8)+dp(46)).coerceAtLeast(dp(120))

/**
 * 重算两个 spacer 的高度。
 *
 * 列表总高度 = 行数 × 行高，与「一行一个 View 全量常驻」时完全一致，
 * 因此滚动条范围、恢复位置、`gridAtBottom` 的判定都不变；
 * 但撑起这个高度只需要两个 Space，不需要真的建出那几百行。
 */
internal fun MainActivity.updateGridSpacers() {
    val stride=rowStride()
    val total=gridRowCount()
    val start=gridWindowStart.coerceAtLeast(0)
    val end=if(gridWindowStart<0) 0 else start+gridRows.size
    setSpacerHeight(gridTopSpacer,start*stride)
    setSpacerHeight(gridBottomSpacer,((total-end).coerceAtLeast(0))*stride)
}

/** 只在高度真的变了才写回：滚动回调每次都会走到这里，无谓的 requestLayout 会多花一帧布局。 */
internal fun MainActivity.setSpacerHeight(spacer: View?,height: Int) {
    val params=spacer?.layoutParams as? LinearLayout.LayoutParams ?: return
    if(params.height==height) return
    params.height=height
    spacer.layoutParams=params
}

/**
 * 只物化视口附近那几行，并把两端进出的行回收进 [gridRowPool]。
 *
 * **整个函数的代价只与窗口大小有关，与已经加载了多少部剧无关**——
 * 这就是「加载几百个卡片之后越来越卡」的根治点。滚动时通常只增删 0~1 行。
 *
 * [focusIndex] 指定「必须可见」的那张卡：焦点在窗口外时（追加后落到新行、
 * 恢复上次焦点）把它所在的行一并纳入，否则请求焦点会落空。
 */
internal fun MainActivity.syncGridWindow(force: Boolean=false,focusIndex: Int?=null) {
    val window=gridWindow ?: return
    val scroll=gridScroll ?: return
    if(gridSyncing) return
    val total=gridRowCount()
    if(total==0) return
    gridSyncing=true
    try { syncGridWindowLocked(force,focusIndex,window,scroll,total) } finally { gridSyncing=false }
}

internal fun MainActivity.syncGridWindowLocked(force: Boolean,focusIndex: Int?,window: LinearLayout,scroll: ScrollView,total: Int) {
    val stride=rowStride()
    val viewport=scroll.height
    // 视口上下各多留一行：焦点放大与「下一页」预取都需要相邻行已经在树上。
    var first=if(viewport<=0) 0 else ((scroll.scrollY-dp(24))/stride-1).coerceAtLeast(0)
    var last=if(viewport<=0) minOf(total-1,GRID_WINDOW_MIN_ROWS-1)
        else ((scroll.scrollY+viewport+dp(24))/stride+1).coerceAtLeast(first).coerceAtMost(total-1)
    focusIndex?.let { index ->
        val row=(index/GRID_COLUMNS).coerceIn(0,total-1)
        // 焦点行不在视口算出来的窗口里时，**以它为准重开一个窗口**，
        // 而不是把「视口到它」之间的行全拉进来——那可能是几百行。
        // 反过来的写法（first=min、last=max）在「恢复的焦点离视口很远」时会让区间反向，
        // 结果是一个空窗口：spacer 撑出几百行的空白，而焦点目标永远不会被物化。
        if(row<first || row>last) {
            first=row
            last=(row+GRID_WINDOW_MIN_ROWS-1).coerceAtMost(total-1)
        }
    }
    // 硬上限：任何情况下都不物化超过 [GRID_WINDOW_MAX_ROWS] 行。
    if(last-first+1>GRID_WINDOW_MAX_ROWS) {
        val focusRow=focusIndex?.let { it/GRID_COLUMNS }
        last=when {
            focusRow!=null && focusRow<=first+GRID_WINDOW_MAX_ROWS-1 -> first+GRID_WINDOW_MAX_ROWS-1
            focusRow!=null -> { first=(focusRow-GRID_WINDOW_MAX_ROWS+1).coerceAtLeast(0); first+GRID_WINDOW_MAX_ROWS-1 }
            else -> first+GRID_WINDOW_MAX_ROWS-1
        }
        last=last.coerceAtMost(total-1)
    }
    if(!force && gridWindowStart==first && gridRows.size==last-first+1) { updateGridSpacers(); return }
    // 两端对齐：中间那几行走「只换绑定数据」的复用路径，因此滚动时视口内的卡片不会重建。
    val keepFrom=maxOf(first,gridWindowStart)
    val keepTo=minOf(last,gridWindowStart+gridRows.size-1)
    if(gridRows.isEmpty() || keepFrom>keepTo) {
        gridRows.toList().forEach(::recycleGridRow)
        gridRows.clear(); gridWindowStart=first
    } else {
        // 丢掉前端多出来的行。**每丢一行窗口起点必须跟着 +1**：`gridWindowStart` 是
        // `gridRows[0]` 的行号，删了首行却不推进起点，循环条件就永远成立，
        // 一路删到空表再取 `first()` 就是 `NoSuchElementException`（2026-10-14 的崩溃）。
        while(gridWindowStart<keepFrom && gridRows.isNotEmpty()) {
            recycleGridRow(gridRows.first()); gridWindowStart++
        }
        // 丢掉后端多出来的行：起点不动，只是变短（size 缩小，条件会自然收敛）。
        while(gridRows.isNotEmpty() && gridWindowStart+gridRows.size-1>keepTo) recycleGridRow(gridRows.last())
        if(gridRows.isEmpty()) gridWindowStart=first
    }
    // 补齐到 [first,last]：前端补完再补后端，插进 window 的位置按行号算。
    while(gridWindowStart>first) { gridWindowStart--; materializeGridRow(gridWindowStart,atFront=true) }
    while(gridWindowStart+gridRows.size-1<last) materializeGridRow(gridWindowStart+gridRows.size,atFront=false)
    updateGridSpacers()
    if(gridRowStride<=0) measureRowStride()
}

/**
 * 同步量一次行高。
 *
 * 行高 = 海报高度 + 剧名 + 状态行 + 卡片内边距 + 行间距，与卡片里的文字度量有关，
 * 因此不能写死一个常数（系统字号放大时会变）。这里量的是**行容器**：
 * 它已经量过（或者现在就量一次），量与布局走的是同一套规则，比单量一张卡可靠。
 * 量到之后 spacer 的高度与滚动位置一次到位，不必再等异步回调纠偏。
 */
internal fun MainActivity.measureRowStride() {
    val line=gridRows.firstOrNull()?.line ?: return
    if(line.width<=0 && gridCardWidth>0) line.measure(
        View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels-2*pageInset(),View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED)
    )
    val measured=(if(line.height>0) line.height else line.measuredHeight)+dp(6)
    if(measured>dp(6) && measured!=gridRowStride) { gridRowStride=measured; updateGridSpacers() }
}

/** 取一个行容器：优先用回收池里的（View 与卡片都复用），池空才新建。 */
internal fun MainActivity.obtainGridRow(): GridRow {
    gridRowPool.removeLastOrNull()?.let { holder -> return holder }
    val line=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    val holder=GridRow(line)
    repeat(GRID_COLUMNS) {
        val card=PosterCard(this,gridPosterHeight,::loadCover)
        wireGridCard(card)
        line.addView(card,lp(gridCardWidth,-2).apply { if(it<GRID_COLUMNS-1) rightMargin=gridGap })
        holder.cards+=card
    }
    return holder
}

/** 把一个行容器放进窗口（[atFront] 决定插在最前还是最后）并绑定它那一行的数据。 */
internal fun MainActivity.materializeGridRow(index: Int,atFront: Boolean) {
    val window=gridWindow ?: return
    val holder=obtainGridRow()
    holder.index=index
    // 复用的行可能带着上一页的海报高度（同值不重设，见 PosterCard.resizePoster）。
    holder.cards.forEach { card -> card.resizePoster(gridPosterHeight) }
    bindGridRow(holder)
    // 行间距 6dp：与「一行一个 View」时代一致，spacer 的行高按「行高 + 行间距」算。
    window.addView(holder.line,if(atFront) 0 else window.childCount,lp(-1,-2).apply { bottomMargin=dp(6) })
    if(atFront) gridRows.add(0,holder) else gridRows.add(holder)
}

/** 把一行交回回收池：从窗口摘下来，但 View 与卡片都留着复用。 */
internal fun MainActivity.recycleGridRow(holder: GridRow) {
    gridWindow?.removeView(holder.line)
    gridRows.remove(holder)
    // 池子封顶：滚动时同时进出的行只有个位数，留 4 行足够，
    // 免得极端情况（快速来回甩）把池子撑成第二个「全量列表」。
    if(gridRowPool.size<4) gridRowPool+=holder
}

/** 按 [GridRow.index] 绑定这一行里每张卡的剧集、状态与封面（同值不重设，见 [PosterCard.bind]）。 */
internal fun MainActivity.bindGridRow(holder: GridRow) {
    holder.cards.forEachIndexed { column,card ->
        val index=holder.index*GRID_COLUMNS+column
        val item=gridItems.getOrNull(index)
        if(item==null) {
            // 最后一行不满时留空槽：卡片留在原位（保持列对齐）但不显示，
            // 封面也清掉——不为一张看不见的卡留住位图。
            card.series=null
            card.visibility=View.INVISIBLE
            card.setCover("")
            return@forEachIndexed
        }
        card.visibility=View.VISIBLE
        bindGridCard(card,item)
    }
}

/**
 * 一张海报卡的**数据绑定**（不含监听器）：监听器在 [wireGridCard] 里只装一次，
 * 因此滚出再滚回来只是换一遍文字与封面，不再新建 View、不再重复挂监听。
 */
internal fun MainActivity.bindGridCard(card: PosterCard,item: Series) {
    val position=if(tab==TAB_RANK) ranking?.positions?.get(item.id) else null
    val rank=position?.rank
    val progress=if(collection=="history") library.progress(item.id) else null
    val statusText=when {
        collection=="favorites" -> library.favoriteLabel(item.id)
        // 榜单偶尔整页没有热度值（服务端 SSR 抽风）：退回剧集自己的角标，不再整屏都是「热度暂无」。
        tab==TAB_RANK -> position?.heat?.takeIf { it.isNotBlank() } ?: item.badge.ifBlank { rankBoard.label }
        progress!=null -> if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}"
        else -> item.badge
    }
    val badgeText=when {
        tab==TAB_RANK -> rank?.let { "第 $it 名" } ?: ""
        else -> item.badge.takeIf { it.isNotBlank() && it!=statusText }
            ?.let { value -> if(value.length<=9) value else value.take(8)+"…" } ?: ""
    }
    card.series=item
    card.tag=item.id
    card.visibility=View.VISIBLE
    card.bind(item,statusText,badgeText,
        progress?.takeIf { it.duration>0 }?.let { (it.position*100/it.duration).toInt().coerceIn(0,100) },
        rank!=null && rank<=3)
}

/**
 * 装一张卡片的监听器：**只在卡片新建时调一次**。
 *
 * 卡片会被复用，因此回调里一律通过 [PosterCard.series] 取「这一张卡现在是谁」，
 * 不捕获某一次的 item。
 */
internal fun MainActivity.wireGridCard(card: PosterCard) {
    card.setOnClickListener { card.series?.let { item -> openSeries(item,collection=="history" || (tab==TAB_PROFILE && accountTab=="history")) } }
    card.setOnLongClickListener { card.series?.let { item -> seriesMenu(item,card) }; true }
    card.setOnKeyListener { _,key,event ->
        val item=card.series
        when {
            key==KeyEvent.KEYCODE_MENU -> {
                if(event.action==KeyEvent.ACTION_UP && item!=null) seriesMenu(item,card)
                true
            }
            // 焦点在窗口的上下边界上、而目标行还没物化时，先把它物化再按索引落过去。
            // 已经物化的情况交给 nextFocusUpId / nextFocusDownId（框架自己走，不经过这里）。
            (key==KeyEvent.KEYCODE_DPAD_UP || key==KeyEvent.KEYCODE_DPAD_DOWN) && event.action==KeyEvent.ACTION_DOWN -> {
                val holder=gridRows.firstOrNull { it.line===card.parent } ?: return@setOnKeyListener false
                val column=holder.cards.indexOf(card)
                val row=holder.index+(if(key==KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1)
                val target=row*GRID_COLUMNS+column
                when {
                    // 第一行再按上键：焦点交回页头那一行（榜单 / 分类芯片、搜索框）。
                    // 页头收着的时候这里顺手把它展开，否则焦点会落在一条高度为 0 的看不见的行上。
                    key==KeyEvent.KEYCODE_DPAD_UP && row<0 -> {
                        val head=headFocusTarget()
                        if(head!=null) { focusHead(head); true } else false
                    }
                    // 目标列在最后一行不满时没有卡片：这一下交回框架，由 nextFocusDownId
                    // 落到「展示更多」上，而不是被吞掉什么都不做。
                    row in 0 until gridRowCount() && target in gridItems.indices -> {
                        // 只有焦点**真的移动了**才吃掉这一下：目标行因为窗口还没物化而落空时，
                        // 交回框架走 nextFocusUpId / nextFocusDownId，不能变成「按了没反应」。
                        focusGridIndex(target,false)
                        gridFocusIndex()==target
                    }
                    // 最后一行再按下键就是「继续往下看」：不经过底部按钮，直接把下一页接上。
                    key==KeyEvent.KEYCODE_DPAD_DOWN && isGridTail(holder) && appendCatalog() && hasMore -> { loadMore(); true }
                    else -> false
                }
            }
            else -> false
        }
    }
    card.setOnFocusChangeListener { _,focused ->
        card.setFocused(focused)
        if(focused) {
            val holder=gridRows.firstOrNull { it.line===card.parent }
            card.series?.let { item ->
                catalogFocus=item.id; loadCover(card.image,item.cover)
                // 焦点走到倒数两行就把下一页取回来，用户真按下去时内容已经在手上。
                if(holder!=null && holder.index>=gridRowCount()-2) prefetchNextPage()
            }
            if(holder==null) return@setOnFocusChangeListener
            // 顺手把下一行的封面按「预取」优先级排上队：用户按下键时图已经在内存缓存里。
            // 队列满时先被丢掉的正是预取，因此不会饿着当前可见的卡（见 ArtworkLoader.MAX_PENDING）。
            gridRows.firstOrNull { it.index==holder.index+1 }?.cards?.forEach { next ->
                next.series?.cover?.takeIf { it.isNotBlank() }?.let { url -> prefetchCover(next.image,url) }
            }
            if(!gridRestoring) revealRow(holder.index,true)
        }
    }
}

/** 把某一行带进视口：窗口化之后行号就是坐标，不需要等布局。 */
internal fun MainActivity.revealRow(row: Int,smooth: Boolean) {
    val scroll=gridScroll ?: return
    val stride=rowStride()
    val top=row*stride
    val target=when {
        top<scroll.scrollY -> top
        top+stride>scroll.scrollY+scroll.height -> (top+stride-scroll.height).coerceAtLeast(0)
        else -> return
    }
    if(smooth) scroll.smoothScrollTo(0,target) else scroll.scrollTo(0,target)
}

/**
 * 把焦点按**全局索引**落到某张卡上。
 *
 * 目标行不在当前窗口里时，先把列表滚到那一行、再按新的视口算窗口——
 * 而不是「把窗口拉大到包含它」：后者在「恢复的焦点离视口很远」时会把中间几百行全物化出来。
 * 滚动是同步的（不用平滑动画），因此后面算出来的窗口与坐标都是确定的。
 */
/**
 * 只在**当前没有任何控件持有焦点**时，才把焦点补到 [target] 上。
 *
 * 这是全站「不主动抢焦点」原则的落点工具（见类头那段说明）：列表重建、异步数据落地、
 * 空页兜底这些**不是用户动作**的场合，一律不许把焦点从用户正按着的地方搬走；
 * 但「焦点真的丢了」（控件被摘掉、页面刚建好）时必须补一个确定的落点，
 * 否则系统会把它交给新布局里第一个可聚焦控件，位置不可预期。
 *
 * 返回这次真正拿到焦点的控件（没补就是 null），调用方可以据此决定要不要滚动。
 */
internal fun MainActivity.ensureFocusView(target: () -> View?): View? {
    if(focusHeld()) return null
    val view=target() ?: return null
    view.requestFocus()
    return view.takeIf { it.hasFocus() }
}

/** 同上，给「动作本身不返回控件」的落点用（例如按索引落焦点）。 */
internal fun MainActivity.ensureFocus(action: () -> Unit) {
    if(focusHeld()) return
    action()
}

/** 当前有没有控件**真的**握着焦点（被摘掉的控件不算）。 */
internal fun MainActivity.focusHeld(): Boolean {
    val current=root.findFocus() ?: return false
    return current.isAttachedToWindow
}

internal fun MainActivity.focusGridIndex(index: Int,smooth: Boolean=true) {
    if(index !in gridItems.indices) return
    val row=index/GRID_COLUMNS
    val scroll=gridScroll
    if(scroll!=null && (gridRows.none { it.index==row })) {
        val stride=rowStride()
        val top=row*stride
        val target=when {
            top<scroll.scrollY -> top
            top+stride>scroll.scrollY+scroll.height -> (top+stride-scroll.height).coerceAtLeast(0)
            else -> scroll.scrollY
        }
        if(target!=scroll.scrollY) scroll.scrollTo(0,target)
    }
    syncGridWindow(focusIndex=index)
    val holder=gridRows.firstOrNull { it.index==row } ?: return
    val card=holder.cards.getOrNull(index%GRID_COLUMNS) ?: return
    card.requestFocus()
    if(smooth) revealRow(row,true)
}

/**
 * 重绑当前窗口那几行（剧集状态变了：收藏 / 历史进度 / 榜单名次刷新）。
 *
 * 窗口化之后「就地更新一张卡」不再按 id 找控件，而是重绑可见的那几十张——
 * 代价恒定，且不会把更新漏在还没物化的行上（它们物化时自然会绑到最新状态）。
 */
internal fun MainActivity.rebindGridRows() {
    gridRows.forEach { holder -> if(holder.line.isAttachedToWindow) bindGridRow(holder) }
}

/** 当前焦点所在的全局索引；焦点不在栅格里时返回 null。 */
internal fun MainActivity.gridFocusIndex(): Int? {
    gridRows.forEach { holder ->
        val column=holder.cards.indexOfFirst { it.hasFocus() }
        if(column>=0) return holder.index*GRID_COLUMNS+column
    }
    return null
}

/**
 * 重算栅格的焦点链。
 *
 * 窗口化之后这里只处理**当前物化的那几行**（≤ 一屏 + 2 行），因此代价与已加载数量无关。
 * 窗口上下边界上的「目标行还没物化」由 [wireGridCard] 的按键处理按索引补物化，
 * 因此链在这些边界上写成「指向自己」也不会把焦点卡死。
 */
internal fun MainActivity.wireGridFocus(prev: View?,next: View?) {
    val rows=gridRows.sortedBy { it.index }
    if(rows.isEmpty()) return
    // 栅格第一行按上键回到它正上方那一行（见 [headFocusTarget]）。
    val headUp=headFocusTarget()?.id
    val first=rows.first().cards.firstOrNull()?.id ?: return
    val lastRow=gridRowCount()-1
    rows.forEachIndexed { position,holder ->
        val cards=holder.cards
        cards.forEachIndexed { column,card ->
            card.nextFocusLeftId=cards[(column-1).coerceAtLeast(0)].id
            card.nextFocusRightId=cards[(column+1).coerceAtMost(cards.lastIndex)].id
            card.nextFocusUpId=when {
                position>0 -> rows[position-1].cards[minOf(column,rows[position-1].cards.size-1)].id
                holder.index==0 -> headUp ?: card.id
                else -> card.id
            }
            card.nextFocusDownId=when {
                position<rows.lastIndex -> rows[position+1].cards[minOf(column,rows[position+1].cards.size-1)].id
                holder.index>=lastRow -> next?.takeIf { it.isFocusable }?.id
                    ?: prev?.takeIf { it.isFocusable }?.id ?: card.id
                else -> card.id
            }
        }
    }
    // 导航栏那排圆形入口按下键进内容第一张卡；「我的」页例外——内容上面还夹着身份行，
    // 先落身份行（未登录时那一行里的「导入账号参数」是唯一入口，跳过它就再也够不着）。
    val navDown=if(tab==TAB_PROFILE) profileHeadControl()?.id ?: first else first
    navIcons().forEach { it.nextFocusDownId=navDown }
    historyManage?.nextFocusDownId=first
    // 榜单 / 一级分类芯片在页头最上面一行：按下键先进自己这一页的第二行（二级分类），
    // 没有第二行时直接进内容第一张卡。二级分类芯片按下键就是内容第一张卡。
    boardButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: first }
    categoryGroupButtons.values.forEach { it.nextFocusDownId=categoryTagButtons[categoryPath]?.id ?: first }
    categoryTagButtons.values.forEach { it.nextFocusDownId=first }
    // 「我的」页的焦点链：芯片 ↓ 身份行 ↓ 内容第一张卡（方向连续，不跳过中间那一行）。
    // 少了这一条芯片就指向自己（建芯片时为了避免「按下键没反应」先设成了自己），
    // 表现出来就是「焦点在收藏 / 历史上按向下，焦点停在原地不动」。
    val headDown=profileHeadControl()?.id ?: first
    accountButtons.values.forEach { it.nextFocusDownId=headDown }
    profileHeadControls().forEach { control ->
        control.nextFocusDownId=first
        accountButtons[accountTab]?.let { chip -> control.nextFocusUpId=chip.id }
    }
    // 搜索页：输入框与「搜索 / 历史 / 手机输入」都在导航栏里，按下键一律先落进
    // 输入框下面那一行（提示词 / 热搜词），没有那一行时直接进内容第一张卡。
    val hintDown=suggestButtons.firstOrNull()?.id ?: first
    searchInput?.nextFocusDownId=hintDown
    searchButton?.nextFocusDownId=hintDown
    searchActions.forEach { it.nextFocusDownId=hintDown }
    // 提示词 / 热搜芯片按下键进内容第一张卡。芯片是**渲染之后才有的**，
    // 渲染那一刻栅格可能还没铺好（那时它们只能指向自己），因此这里每次重排都补一次。
    suggestButtons.forEach { it.nextFocusDownId=first }
    // 列表最后一行按下键落在底部「展示更多」上，再按一次（确认或下键）就追加下一页。
    moreButton?.nextFocusUpId=rows.last().cards.firstOrNull()?.id ?: first
}
/**
 * 已显示进度。榜单自带总页数，所以能给「N / M 部」；
 * 搜索结果只知道自己追加到第几页，就只报已显示数量，加载完再明确说「全部」。
 */
internal fun MainActivity.moreCountText(): String {
    val total=(ranking?.totalPages ?: 0)*pageSize
    return when {
        !hasMore -> "已显示全部 ${catalog.size} 部"
        total>catalog.size -> "已显示 ${catalog.size} / $total 部"
        else -> "已显示 ${catalog.size} 部"
    }
}
/** 追加一页时把两页的名次与热度合起来，否则先加载的那几页会丢掉名次。 */
internal fun MainActivity.mergeRanking(previous: ComicRanking?,next: ComicRanking?): ComicRanking? = when {
    previous==null -> next
    next==null -> previous
    else -> next.copy(positions=previous.positions+next.positions)
}
