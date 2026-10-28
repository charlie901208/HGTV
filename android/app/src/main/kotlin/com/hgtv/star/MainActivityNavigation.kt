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
import com.hgtv.star.MainActivity.CatalogState
import com.hgtv.star.tv.ToggleChip
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「顶部五个入口、切换页面、分类与榜单芯片、页头导航与页标题」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.switchTab(next: Int) {
    // 本次运行第一次进这一页（见 [tabVisited]）。
    val firstVisit=tabVisited.add(next)
    // 首次进入分类页：回到默认的「漫剧 · 全部」。
    // [categoryPath] 本来就从默认值起步（见字段声明），这里显式写一次是为了挡住
    // 「本次运行里先在分类页挑过别的、之后又切走再切回来」——那属于**再次**进入，
    // 由 [tabVisited] 区分，不会走到这一支。
    if(firstVisit && next==TAB_CATEGORY) categoryPath=CategoryCatalog.defaultTag.path
    if(next!=TAB_RANK && tab==TAB_RANK) boardButtons.values.firstOrNull { it.hasFocus() }?.clearFocus()
    if(next!=TAB_CATEGORY && tab==TAB_CATEGORY) {
        (categoryGroupButtons.values + categoryTagButtons.values).firstOrNull { it.hasFocus() }?.clearFocus()
    }
    // 换榜单 / 换分类的请求还在路上时，屏上那份列表属于**上一条**选择：
    // 照存会让用户切回来时看到别的分类 / 榜单的剧集。这时只存一个空页占位，
    // 切回来自然会重新取一次（见 [chipFocusKey]）。
    tabState[tab]=CatalogState(page,if(chipFocusKey!=null) emptyList() else catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
    tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
    // 首页的四个剧架就在内存里（[homeFeed]），切回来不需要重新打四次网络；
    // 想要最新的一份按顶部圆形「刷新」。搜索页与榜单页仍然是「没内容才去取」。
    val needsLoad=when(next) {
        TAB_HOME -> homeFeed.isEmpty()
        TAB_RANK, TAB_CATEGORY -> catalog.isEmpty()
        TAB_SEARCH -> query.isNotBlank() && catalog.isEmpty()
        else -> false
    }
    // 落点分两种：
    // - **首次**进分类 / 榜单 / 我的页：焦点落在页头那枚芯片上——分类页是二级分类的「全部」
    //   （默认选中「漫剧 · 全部」），榜单页是当前榜单「漫剧」，我的页是第一栏「历史」。
    //   这三页都是「先挑一个再看」，焦点直接跳到内容第一张卡会让人不知道自己看的是哪一类。
    // - 其余情况（含首页、搜索页、再次进这几页）：落到内容第一张卡，用户是来看内容的。
    if(firstVisit && (next==TAB_RANK || next==TAB_CATEGORY || next==TAB_PROFILE)) {
        chipFocusKey=when(next) {
            TAB_RANK -> "board:"+rankBoard.storedValue
            TAB_CATEGORY -> "tag:"+categoryPath
            else -> "account:"+accountTab
        }
        focusFirstCardOnLanding=false
    } else if(needsLoad) focusFirstCardOnLanding=true
    // 「我的」页的四栏是账号数据：每次进来都重取一次，别的设备（手机）上刚看过的才会出现。
    if(next==TAB_PROFILE) loadAccount(force=true)
    showCatalog(load=needsLoad)
}
/**
 * 切换排行榜页看的榜单。首页四个剧架的「查看全部」走的也是这里——
 * 它们与榜单页读的是同一份数据（[ContentRepository.rank]），只是入口不同。
 *
 * [fromHome] 为真表示这是从首页剧架进来的：顺手把顶部标签切到排行榜，
 * 让用户看到的就是榜单页本身（页面名、来源行、底部加载都是同一套）。
 */
internal fun MainActivity.switchRankBoard(board: RankBoard,fromHome: Boolean=false) {
    if(rankBoard==board && !fromHome && tab==TAB_RANK) return
    // 从首页剧架「查看全部」进来也算进过榜单页：之后点「榜单」图标不该再按「首次进入」处理。
    tabVisited.add(TAB_RANK)
    // 这一下是「点了榜单页里的芯片」还是「从首页剧架进来的」，落点完全不同：
    // 前者焦点留在那枚芯片上（用户还在挑榜单），后者落到内容第一张卡（用户是来看榜单的）。
    val fromChip=!fromHome && tab==TAB_RANK && boardButtons.values.any { it.hasFocus() }
    boardButtons.values.firstOrNull { it.hasFocus() }?.clearFocus()
    rankBoard=board
    boardButtons.forEach { (option,chip) -> (chip as? ToggleChip)?.setActive(option==board) }
    // 榜单页的内容与页码都跟着榜单走：换榜就是换一份列表。
    tabState.remove(TAB_RANK); tabFocus.remove(TAB_RANK)
    releaseCatalogViews()
    // 只重置页码与「记住的焦点卡」：列表本身留给 [showCatalog] 决定清不清——
    // 点了芯片时它要留着旧的一屏顶住这一段时间（否则页面上只剩一行「正在加载…」，看起来就是闪屏）。
    page=1; catalogFocus=""
    // 从首页剧架进来：顺手把顶部标签切到排行榜，用户看到的就是榜单页本身
    // （页面名、来源行、底部加载都是同一套）。少了这一步，[showCatalog] 会按当前标签
    // 重新渲染首页，表现出来就是「在剧架最右边按右键 / 点『查看全部』进不去榜单」。
    if(fromHome) tab=TAB_RANK
    // 换榜单就是换一份列表。落点分两种：在榜单页点了另一枚芯片 → 焦点留在那枚芯片上；
    // 从首页剧架进来（用户是来看榜单的）→ 内容落地后落到第一张卡。
    chipFocusKey=if(fromChip) "board:"+board.storedValue else null
    focusFirstCardOnLanding=!fromChip
    showCatalog(load=true)
}
/**
 * 切换分类页的大类：选这个大类下的「全部」，并重新取第 1 页。
 *
 * 大类与它的小类是一套联动：换大类时小类那一行整行换掉（见 `showCatalog` 里的分类页分支），
 * 因此这里只管改选择与页码。
 */
internal fun MainActivity.switchCategoryGroup(group: CategoryGroup) {
    val tag=group.tags.firstOrNull() ?: return
    if(tag.path==categoryPath && tab==TAB_CATEGORY) return
    selectCategory(tag.path)
}
/** 切换分类页的小类（同一个大类内）。 */
internal fun MainActivity.switchCategoryTag(tag: CategoryTag) {
    if(tag.path==categoryPath && tab==TAB_CATEGORY) return
    selectCategory(tag.path)
}
/**
 * 选中一个分类并重新取第 1 页。
 *
 * 分类页是「往下接」的长列表：换分类等于换一份列表，因此页码与滚动位置都从头开始，
 * 焦点在内容落地后落到第一张卡（与换榜单同一条规则）；
 * 芯片的选中态由重建页面时按 [categoryPath] 重新算。
 */
internal fun MainActivity.selectCategory(path: String) {
    // 在分类页里挑过分类就算进过这一页（首次进入的落点规则因此只生效一次）。
    tabVisited.add(TAB_CATEGORY)
    // 点的是大类还是小类芯片，决定焦点落回哪一行：两者都记进 [chipFocusKey]。
    // 必须在 [releaseCatalogViews] 之前读——它会把两张芯片表清空。
    val groupChip=categoryGroupButtons.entries.firstOrNull { it.value.hasFocus() }?.key
    val tagChip=categoryTagButtons.entries.firstOrNull { it.value.hasFocus() }?.key
    categoryPath=path
    tabState.remove(TAB_CATEGORY); tabFocus.remove(TAB_CATEGORY)
    releaseCatalogViews()
    // 同换榜单：只重置页码与记住的焦点卡，旧列表留到新的一页落地再换（见 [showCatalog]）。
    page=1; catalogFocus=""
    // 换分类就是换一份列表。焦点留在**刚点的那一枚**芯片上：点大类就回大类那一枚，
    // 点小类就回小类那一枚（不再先跑到一级分类、也不再跟着内容落地跳到第一张卡）。
    chipFocusKey=when {
        groupChip!=null -> "group:"+groupChip.path
        tagChip!=null -> "tag:"+tagChip
        else -> null
    }
    focusFirstCardOnLanding=chipFocusKey==null
    showCatalog(load=true)
}
/** 本机记录集合页（收藏 / 历史 / 稍后观看）；远端列表只有榜单页一种，不再有「热门集合页」。 */
internal fun MainActivity.showCollection(kind: String) {
    collection=kind; page=1; catalogFocus=""
    // 历史页带账号那一半：进来就重取，不然要重开 App 才看得到别的设备上的新记录。
    if(kind=="history") loadAccount(force=true)
    showCatalog()
}
/** 集合页标题（本机记录页专用）。 */
internal fun MainActivity.collectionName(kind: String)=when(kind) {
    "favorites" -> "我的收藏"; "history" -> "历史记录"; "later" -> "稍后观看"; else -> "本机记录"
}
/**
 * 顶部导航栏左侧显示的页面名。
 *
 * 首页与搜索 / 设置页显示品牌「红果TV」；二级界面（收藏 / 历史 / 稍后观看）显示自己的名字，
 * 排行榜页显示**当前榜单的名字**——这些页面原来的标题行因此可以整行去掉。
 */
internal fun MainActivity.pageTitle(): String = when {
    // 「我的」页优先：它下面那四个栏目也是 collection，但页面名固定是「我的」。
    tab==TAB_PROFILE -> "我的"
    collection!=null -> collectionName(collection.orEmpty())
    tab==TAB_RANK -> "热播榜"
    tab==TAB_CATEGORY -> "分类"
    // 搜索页的导航栏里还挤着输入框与三个按钮，标题只留两个字给它们让宽度。
    tab==TAB_SEARCH -> "搜索"
    tab==TAB_SETTINGS -> "设置"
    else -> "红果TV"
}
/**
 * 当前标签对应的顶部图标入口。`nav` 是五个页面入口（搜索 / 分类 / 榜单 / 设置 / 个人信息），
 * 顺序与 [TAB_SEARCH]…[TAB_SETTINGS] 一一对应。
 *
 * 首页（[TAB_HOME]）没有自己的入口：品牌「红果TV」是标识而不是按钮，不参与焦点链，
 * 这里返回 null——调用方跳过聚焦即可（回主界面走返回键）。
 */
internal fun MainActivity.navEntry(index: Int = tab): View? = nav.getOrNull(index-1)

/**
 * 顶部这一排图标入口。
 *
 * 这里按 `visibility` 过滤而**不是** `isShown`：页面是在挂上窗口之前建好的，
 * 那时 `isShown` 一律是 false，用它过滤会得到一张空表——
 * 于是「圆形入口按下键进内容」这条焦点链根本没接上（表现出来就是「按上回不到按钮 / 按下不动」）。
 */
internal fun MainActivity.navIcons(): List<View> = nav.filter { it.visibility==View.VISIBLE }
/**
 * 页头里「把焦点交回页头」时该落到哪一枚控件。
 *
 * 榜单页是四个榜单芯片、分类页是大类芯片（两页都有自己的切换行），其余页面退到顶部圆形入口。
 * 首页返回 null：品牌不是按钮，调用方改落到第一栏第一张卡。
 */
internal fun MainActivity.firstHeadControl(): View? = when(tab) {
    TAB_RANK -> boardButtons[rankBoard]
    // 分类页：当前这一组的一级分类芯片（不是「第一枚」），与页头里选中的那一枚一致。
    TAB_CATEGORY -> CategoryCatalog.groupOf(categoryPath)?.let { categoryGroupButtons[it] }
        ?: categoryGroupButtons.values.firstOrNull()
    // 搜索页的内容入口就是输入框：焦点落在它上面，而不是顶部那排圆形入口。
    TAB_SEARCH -> searchInput
    // 「我的」页：页头里与内容直接相邻的是当前那一栏的芯片。
    // 「我的」页：内容第一行按上键先回身份行（头像 / 重新导入 / 清除 / 重新加载），
    // 那一行在芯片与内容之间，方向上是连续的。
    TAB_PROFILE -> profileHeadControl() ?: accountButtons[accountTab] ?: accountButtons.values.firstOrNull()
    TAB_HOME -> null
    else -> navEntry()
}
/**
 * 把 [chipFocusKey] 的 key 查回页面上**当前那一枚**芯片。
 *
 * 查不到（芯片还没建好、或换了页面）就返回 null，调用方退回原来的落点规则。
 */
internal fun MainActivity.chipControl(key: String?): View? {
    val parts=key?.split(':',limit=2)?.takeIf { it.size==2 } ?: return null
    return when(parts[0]) {
        "board" -> RankBoard.entries.firstOrNull { it.storedValue==parts[1] }?.let { boardButtons[it] }
        "group" -> CategoryCatalog.groups.firstOrNull { it.path==parts[1] }?.let { categoryGroupButtons[it] }
        "tag" -> categoryTagButtons[parts[1]]
        "account" -> accountButtons[parts[1]]
        else -> null
    }
}
/**
 * 栅格第一行按上键时该落到哪一枚控件：页头里「与内容直接相邻」的那一个。
 *
 * 搜索页是输入框、分类页是当前二级分类芯片、榜单页是当前榜单芯片、
 * 本机记录页是它自己的动作按钮，其余页面退到顶部圆形入口。
 * 没有可落的目标时返回 null，调用方把按键交回框架。
 */
internal fun MainActivity.headFocusTarget(): View? = when(tab) {
    // 搜索页：内容第一行按上键先进输入框下面那一行（提示词 / 热搜词），
    // 那一行收起来时才直接回输入框——方向上是连续的。
    TAB_SEARCH -> suggestButtons.firstOrNull() ?: searchInput
    TAB_CATEGORY -> categoryTagButtons[categoryPath]
        ?: CategoryCatalog.groupOf(categoryPath)?.let { categoryGroupButtons[it] }
        ?: categoryGroupButtons.values.firstOrNull()
    TAB_RANK -> boardButtons[rankBoard] ?: boardButtons.values.firstOrNull()
    TAB_PROFILE -> accountButtons[accountTab] ?: accountButtons.values.firstOrNull()
    TAB_HOME -> navEntry() ?: nav.firstOrNull()
    else -> historyManage ?: navEntry()
}
/**
 * 把焦点交给页头里的一个控件。
 *
 * 页头随列表滚动收起时，那一行的高度是 0、alpha 也是 0：焦点请求本身会成功，
 * 但用户什么都看不见（读到的就是「按上没反应」）。因此凡是往页头里落焦点，
 * 一律先把它展开。
 */
internal fun MainActivity.focusHead(view: View) {
    if(topAreaHidden) setTopAreaHidden(false)
    view.requestFocus()
}
/**
 * 换榜单 / 换分类之后，把焦点交回**用户刚点的那一枚**芯片。
 *
 * 页面重建会把旧的芯片整行摘掉，不显式指定的话系统会把焦点交给新布局里第一个可聚焦控件
 * （分类页就是第一枚大类芯片）——表现出来就是「选完二级分类，焦点先跑到一级分类，
 * 再跟着内容落地跳到别处」。芯片建好之后立刻调一次，焦点从头到尾都停在那一枚上。
 * [prefix] 限定这一页该认哪一类芯片（榜单页 `board:`、分类页 `group:` / `tag:`）。
 */
internal fun MainActivity.restoreChipFocus(prefix: String) {
    val key=chipFocusKey ?: return
    if(!key.startsWith(prefix)) return
    val chip=chipControl(key) ?: return
    focusHead(chip)
    // 芯片刚建出来时还没走完布局，这一下请求可能不生效（焦点会落到新布局里第一个
    // 可聚焦控件上，也就是第一枚大类芯片）。等它挂上窗口再补一次。
    // 补的条件收紧到「焦点空着，或落在另一枚芯片上」：用户在这一帧里已经移走焦点时不抢回来。
    chip.post {
        val current=root.findFocus()
        if(chip.isAttachedToWindow && !chip.hasFocus() && (current==null || current is ToggleChip)) focusHead(chip)
    }
}
/** 榜单来源说明：四个榜单都来自官网自己的热播榜，带名次与热度，不再有「按播放量排序」的替代品。 */
internal fun MainActivity.rankSource()=rankBoard.source
/**
 * 页头那一行的切换芯片（榜单页的四个榜单、分类页的三个一级分类）是否放进导航栏里。
 * 屏幕窄或字号放大时退回导航栏下方单独一行——芯片本身按文字宽度排布，
 * 挤在导航栏里会把右边的圆形入口顶出屏幕。
 */
internal fun MainActivity.inlineHeadChips()=resources.configuration.fontScale<=1.2f && widthDp()>=900
/**
 * 一行芯片的四向焦点。
 *
 * - 左右在行内按顺序走；[after] 非空时「最后一枚往右」接它（[after] 往左回最后一枚），
 *   为空时走到两端**停在原地**（不跳出行外）；
 * - 向上交给 [up]：页头最上面那一行传自己（按上键停在原地），
 *   分类页的二级分类传它所属的一级分类芯片；
 * - 向下由 `wireGridFocus` 接上内容区的第一张卡。
 *
 * 左右键在这里**显式接管**：只写 `nextFocusRightId` 时，遥控器右移会被
 * 行外的控件（导航栏那一排圆形入口）抢走，表现出来就是「右键切不动焦点」。
 */
internal fun MainActivity.wireChipRow(chips: List<View>, up: (Int)->View?, after: View?=null) {
    chips.forEachIndexed { index, chip ->
        val left=chips[(index-1).coerceAtLeast(0)]
        val right=if(index==chips.lastIndex) (after ?: chip) else chips[index+1]
        val above=up(index) ?: chip
        chip.nextFocusLeftId=left.id
        chip.nextFocusRightId=right.id
        chip.nextFocusUpId=above.id
        chip.setOnKeyListener { view,key,event ->
            if(event.action!=KeyEvent.ACTION_DOWN) false
            else when(key) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { if(left!==view) { focusHead(left); true } else true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if(right!==view) { focusHead(right); true } else true }
                KeyEvent.KEYCODE_DPAD_UP -> { if(above!==view) { focusHead(above); true } else true }
                else -> false
            }
        }
    }
    // 同一视觉行里紧跟在这些芯片后面的那一枚（导航栏的圆形入口）往左回最后一枚芯片。
    if(after!=null) after.nextFocusLeftId=chips.last().id
}
/**
 * 远端长列表：排行榜页的四个榜单，以及搜索结果。
 * 这些列表都是「往下接」而不是「翻页」：已显示的剧集不动，新的一页接在末尾。
 */
internal fun MainActivity.appendCatalog()=collection==null && (tab==TAB_RANK || tab==TAB_CATEGORY || tab==TAB_SEARCH)
/**
 * 一次全量铺出来的长列表：本机记录集合页（收藏 / 历史 / 稍后观看）与「我的」页的四个栏目。
 *
 * 这些列表的数据全在内存里（本机记录，或已经拉到手的账号数据），**不分页**：
 * 一次全交给栅格，由窗口化只物化视口附近那几行。以前集合页走的是「上一页 / 下一页」，
 * 一页 20 条、每翻一次整页重建，滚动位置还得自己记——与分类页那套长列表不是一套东西。
 */
internal fun MainActivity.fullList()=collection!=null || tab==TAB_PROFILE
/**
 * 搜索结果不再给「展示更多」按钮：滚到列表底部（或焦点走到最后一行）就自动往下取，
 * 底部只留一行不可聚焦的进度文字。
 */
internal fun MainActivity.autoAppendCatalog()=tab==TAB_SEARCH && collection==null
/** 本机记录集合页（我的收藏 / 历史记录 / 稍后观看）。 */
internal fun MainActivity.localCatalog()=collection!=null
/**
 * 本机记录集合页的内容：**本机在前，账号侧补在后面**（与首页那两栏同一套合并规则）。
 *
 * 少了账号这一半，导入了账号参数却在这台设备上没看过剧时，历史页会是空的
 * （账号里的历史只在「我的」页与首页出现过），用户读到的就是「历史记录没有任何记录」。
 */
internal fun MainActivity.localItems(): List<Series> = when(collection) {
    "favorites" -> mergeAccount(library.favorites()) { accountData?.favorites?.map { it.series }.orEmpty() }
    "history" -> mergeAccount(library.history().map { it.series }) { accountData?.history?.map { it.series }.orEmpty() }
    "later" -> library.later()
    else -> library.history().map { it.series }
}
/** 本机在前、账号在后，按 id 去重（同一部剧两边都有时以本机那条为准）。 */
internal fun MainActivity.mergeAccount(local: List<Series>,account: ()->List<Series>): List<Series> {
    val seen=local.map { it.id }.toHashSet()
    return local+account().filterNot { it.id in seen }
}
