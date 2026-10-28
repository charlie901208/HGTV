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
import com.hgtv.star.player.DanmakuSlider
import com.hgtv.star.tv.TvButton
import com.hgtv.star.tv.TvStyle
import com.hgtv.star.tv.TvTheme
import com.hgtv.star.list.showCatalog

// 本文件是从 MainActivity.kt 拆出来的「设置页：外观主题、播放偏好、账号与关于」这一块。
// 这些成员仍然属于 MainActivity，只是以扩展成员（MainActivity.xxx）的形式放在这里，
// 逻辑与拆之前逐行一致；改这里等于改 Activity 本体。

internal fun MainActivity.settings(parent: LinearLayout) {
    // 页面名「设置」已经进了顶部导航栏，这里不再重复一行标题，只留一句说明。
    parent.addView(text("偏好自动保存，下一部剧继续使用。",14f,TvStyle.muted).apply { setPadding(0,dp(10),0,dp(20)) })
    val groups=row().apply { gravity=Gravity.TOP; clipChildren=false; clipToPadding=false }
    val playback=column().apply { clipChildren=false; clipToPadding=false }
    val device=column().apply { clipChildren=false; clipToPadding=false }
    val groupBand=dp(TvStyle.SOFT_BAND_LARGE)
    /** 一个设置分组：大号凸起面板，内容四周留出柔光带。 */
    fun groupPanel(group: LinearLayout) {
        group.setPadding(dp(24)+groupBand,dp(20)+groupBand,dp(24)+groupBand,dp(20)+groupBand)
        group.background=softPanel(TvStyle.surfaceHigh,TvStyle.RADIUS_DIALOG,band=TvStyle.SOFT_BAND_LARGE,depth=1.05f)
    }
    listOf(playback,device).forEachIndexed { i,group ->
        groupPanel(group)
        groups.addView(group,lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(TvStyle.GAP_GROUP) })
    }
    parent.addView(groups)
    fun heading(group: LinearLayout,title: String,subtitle: String) {
        group.addView(text(title,18f,TvStyle.text).apply { setTypeface(null,Typeface.BOLD) },lp(-2,-2))
        group.addView(text(subtitle,14f,TvStyle.muted).apply { setPadding(0,dp(TvStyle.GAP_TIGHT),0,dp(TvStyle.GAP_BLOCK)) })
    }
    /**
     * 设置项就是整行左对齐的常规按钮：高度、圆角、焦点反馈与全站按钮完全一致，只是文字靠左。
     * 行距由 GAP_INLINE 统一给出，不再靠每一项自己写 bottomMargin。
     */
    fun setting(group: LinearLayout,label: String,action: ()->Unit): TvButton {
        val view=TvButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,alignStart=true,action=action)
        group.addView(view,lp(-1,-2).apply { if(group.childCount>1) topMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    /**
     * 网格里的一格（按钮）：与 [setting] 同一个按钮，宽度交给权重（一行两格）。
     * 行距与格距由外面的行容器给，这里不加 topMargin。
     */
    fun gridSetting(row: LinearLayout,label: String,action: ()->Unit): TvButton {
        val view=TvButton(this,label,ButtonSize.NORMAL,ButtonTone.SECONDARY,alignStart=true,action=action)
        row.addView(view,lp(0,-2).apply { weight=1f; if(row.childCount>1) leftMargin=dp(TvStyle.GAP_INLINE) })
        return view
    }
    /**
     * 整栏宽的一行（左标签 + 右滑轨），返回（滑块，这一整行）。
     *
     * 高度与上面那排按钮同一档（[TvStyle.BUTTON_NORMAL] + 两条柔光带 = 60dp），
     * 因此这一栏的三行看起来是同一块料，而不是「按钮 + 两条另一样的东西」。
     *
     * 左侧留白按按钮的文字内边距给（[TvStyle.BUTTON_PAD_NORMAL] + 柔光带 = 34dp）：
     * `TvButton` 的文字正是从这里起排的，所以上下三行的文字左边缘会落在同一条竖线上；
     * 右侧同样留出这段距离，滑轨的百分比读数才不会顶到面板边缘。
     *
     * 与播放设置面板里那两条共用同一个 [DanmakuSlider]、改的是同一份本机偏好，
     * 因此设置页调好之后进播放界面立刻就是新的字号 / 透明度。
     */
    fun fullSlider(group: LinearLayout,label: String,min: Float,max: Float,step: Float,value: Float,onValue: (Float)->Unit): Pair<DanmakuSlider,View> {
        val line=row().apply {
            clipChildren=false; clipToPadding=false; gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(TvStyle.BUTTON_PAD_NORMAL)+dp(TvStyle.SOFT_BAND),0,dp(TvStyle.BUTTON_PAD_NORMAL)+dp(TvStyle.SOFT_BAND),0)
        }
        line.addView(
            text(label,TvStyle.BUTTON_TEXT_NORMAL,TvStyle.text).apply {
                gravity=Gravity.CENTER_VERTICAL; maxLines=1; ellipsize=TextUtils.TruncateAt.END
            },
            lp(dp(110),-2)
        )
        val slider=DanmakuSlider(this,label,min,max,step,value,onValue)
        line.addView(slider,lp(0,-1).apply { weight=1f })
        group.addView(line,lp(-1,dp(TvStyle.BUTTON_NORMAL+2*TvStyle.SOFT_BAND)).apply { topMargin=dp(TvStyle.GAP_INLINE) })
        return slider to line
    }
    heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
    heading(device,"片单与设备","保管记录，保持应用更新")
    // 这些设置项都是全站统一的常规按钮，声明成 TvButton 才能参与下面几栏的焦点串联。
    lateinit var qualityButton: TvButton
    qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
    lateinit var defaultSpeed: TvButton
    defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
    lateinit var frame: TvButton
    frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
    lateinit var autoNextButton: TvButton
    autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
        library.autoNext=!library.autoNext
        autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
    }
    lateinit var backup: TvButton
    backup=setting(device,"手机备份与恢复") {
        runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
            tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                favoriteMonitor.stop(); library.restore(data,restoreSettings)
                tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                showCatalog(); favoriteMonitor.check()
                showHint("记录已合并恢复")
            }
        }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
    }
    lateinit var hidden: TvButton
    hidden=setting(device,"恢复隐藏的剧集  ·  ${library.hidden().size} 部") {
        library.unhideAll(); hidden.text="恢复隐藏的剧集  ·  0 部"
    }
    // 手动清除缓存（封面缩略图等）：收藏、进度、登录不受影响。启动时每 2 天也会自动清一次。
    val clearCacheRow=setting(device,"清除缓存") { clearCache { showHint("缓存已清除") } }
    // 「版本与更新」：打开更新弹窗，可以手动检查、下载并交给系统安装。
    // 有新版本时进入应用会主动提示一次；选过「暂不更新」后不再主动提示，
    // 直到装上任何新版本（见 AppUpdater.declined）。
    lateinit var versionRow: TvButton
    versionRow=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") {
        appUpdater.show(versionRow)
    }
    lateinit var license: TvButton
    license=setting(device,"开源许可") {
        tvTools.info("开源许可",licenseText(),license)
    }
    // —— 弹幕：单独一栏，栏内三行 ——
    // 上面两列原来分别是 8 项与 5 项（还夹着一个「搜索登录态」），高度差一大截；弹幕这四项
    // 只对弹幕生效，抽出来单独成栏之后：左列 4 项、右列 4 项，两列高度相当，弹幕也有了自己的标题。
    //
    // 栏内按「两个离散项并排、两个连续量各占整行」分三行：第一行是 [弹幕显示 | 弹幕速度]
    // ——这两个都是「从几档里选一档」，并排一行才能一眼比较；下面两条滑块（弹幕大小 / 弹幕透明度）
    // 各占一整行——连续量要把滑轨铺满整栏宽度，左端标签、右端百分比读数才排在同一列上，
    // 半屏宽里塞一条滑轨会挤到只剩两百来 dp，看着糙也不好看数。
    val danmaku=column().apply { clipChildren=false; clipToPadding=false }
    groupPanel(danmaku)
    parent.addView(danmaku,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
    heading(danmaku,"弹幕","显示方式 / 速度 / 字号 / 透明度，正在看剧时也能改")
    val danmakuTop=row().apply { clipChildren=false; clipToPadding=false }
    danmaku.addView(danmakuTop,lp(-1,-2))
    // 弹幕显示方式：第一行左格。回调里要用的 syncDanmakuStyleRows 在下面才定义
    //（它要引用另外三项），因此这里先用空动作建好这一格，等四项齐了再补上回调。
    lateinit var danmakuSetting: TvButton
    danmakuSetting=gridSetting(danmakuTop,danmakuLabel("弹幕显示  ·  ")) {}
    // 弹幕移动速度：第一行右格，三档（慢速 / 标准 / 快速）。
    lateinit var danmakuSpeedSetting: TvButton
    danmakuSpeedSetting=gridSetting(danmakuTop,"弹幕速度  ·  ${library.danmakuSpeed.label}") {
        showDanmakuSpeedPicker(danmakuSpeedSetting,false)
    }
    // 弹幕大小：整行一条滑块。
    val scaleCell=fullSlider(danmaku,"弹幕大小",DanmakuStyle.SCALE_MIN,DanmakuStyle.SCALE_MAX,DanmakuStyle.SCALE_STEP,library.danmakuScale) { value ->
        library.danmakuScale=value; danmakuView?.textScale=value
    }
    lateinit var danmakuScaleLine: View
    lateinit var danmakuScaleRow: DanmakuSlider
    danmakuScaleRow=scaleCell.first
    danmakuScaleLine=scaleCell.second
    // 弹幕透明度：整行一条滑块。
    val alphaCell=fullSlider(danmaku,"弹幕透明度",DanmakuStyle.ALPHA_MIN,DanmakuStyle.ALPHA_MAX,DanmakuStyle.ALPHA_STEP,library.danmakuAlpha) { value ->
        library.danmakuAlpha=value; danmakuView?.textAlpha=value
    }
    lateinit var danmakuAlphaLine: View
    lateinit var danmakuAlphaRow: DanmakuSlider
    danmakuAlphaRow=alphaCell.first
    danmakuAlphaLine=alphaCell.second
    // 「按键设置」那一栏的六格与「恢复默认」那一行。链的顺序是 弹幕栏 → 按键栏 → 主题网格，
    // 这里先留好引用，等下面建好那一栏再接线（见 [syncDanmakuStyleRows] 与按键栏那一段）。
    var keyCells: List<TvButton> = emptyList()
    var keyRestore: TvButton? = null
    /**
     * 隐藏弹幕时收起三行（速度那一格 + 字号 / 透明度两条滑块），选回可见档时再放出来。
     *
     * 判定只有一份（[DanmakuMode.showsStyle]），设置页与播放设置面板共用同一条规则。
     *
     * 这里要同时做四件事，缺一件都会出问题：
     * 1. 收起的单位是**行**：第一行右格（速度）与下面两条滑块整行一起 `GONE`——只藏控件
     *    会在左边留一截空标签、整行还占着那一档行距；第一行只剩「弹幕显示」一格后
     *    它按权重撑满整行，看起来就是一条常规设置项；
     * 2. 竖排路径（显示 → 大小 → 透明度 → 主题网格，反向逐级回去）按当前档位重指一遍
     *    ——链是单向写死的，切回可见档之后不会自己长回来；
     * 3. 第一行的横排出口（显示 ↔ 速度）与两条滑块自己的左右出口跟着变：滑块各占整行，
     *    左右键只在值真的变了时被它吃掉（见 [DanmakuSlider]），所以让左右出口指回自己，
     *    按到值两端时不会跳走；隐藏档下第一行只有一格，左右都指回自己；
     * 4. 主题网格首行的上行出口跟着变：可见时整行都回到它正上方那条「弹幕透明度」，
     *    隐藏时回到「弹幕显示」；焦点若正停在被收起的那几行上，也一并退回「弹幕显示」。
     */
    fun syncDanmakuStyleRows() {
        val shown=library.danmakuMode.showsStyle
        danmakuSpeedSetting.visibility=if(shown) View.VISIBLE else View.GONE
        danmakuScaleLine.visibility=if(shown) View.VISIBLE else View.GONE
        danmakuAlphaLine.visibility=if(shown) View.VISIBLE else View.GONE
        danmakuScaleRow.isFocusable=shown
        danmakuAlphaRow.isFocusable=shown
        val themeHead=themeRows[TvTheme.entries.first()] ?: danmakuAlphaRow
        // 弹幕栏下面就是「按键设置」那一栏（再下面才是主题网格）：两处的出口都按当前档位现算，
        // 那一栏还没建好时（理论上不会）落回主题网格，焦点链不会断。
        val keyHead=keyCells.firstOrNull()?.id ?: themeHead.id
        val keyTail=keyRestore?.id ?: themeHead.id
        // 竖排：显示 ↓ 大小 ↓ 透明度 ↓ 按键栏 ↓ 主题网格；反向 主题网格 ↑ 按键栏 ↑ 透明度 ↑ 速度 ↑ 显示。
        // 隐藏档下这三行都不占位，上下都直接落回「弹幕显示」或下一栏。
        danmakuSetting.nextFocusDownId=if(shown) danmakuScaleRow.id else keyHead
        danmakuSpeedSetting.nextFocusDownId=if(shown) danmakuScaleRow.id else danmakuSetting.id
        danmakuScaleRow.nextFocusUpId=danmakuSetting.id
        danmakuScaleRow.nextFocusDownId=if(shown) danmakuAlphaRow.id else danmakuSetting.id
        danmakuAlphaRow.nextFocusUpId=if(shown) danmakuSpeedSetting.id else danmakuSetting.id
        danmakuAlphaRow.nextFocusDownId=if(shown) keyHead else danmakuSetting.id
        // 按键栏第一行往上回到弹幕栏的最后一行（可见档 → 弹幕透明度、隐藏档 → 弹幕显示），
        // 最后一行的下行出口接主题网格第一行：整条竖链只有这一份出口，按档位现指。
        keyCells.firstOrNull()?.nextFocusUpId=if(shown) danmakuAlphaRow.id else danmakuSetting.id
        keyRestore?.nextFocusDownId=themeHead.id
        // 横排：第一行两格互指；两条滑块各占整行，左右出口指回自己（值到两端时就停住）。
        danmakuSetting.nextFocusLeftId=danmakuSetting.id
        danmakuSetting.nextFocusRightId=if(shown) danmakuSpeedSetting.id else danmakuSetting.id
        danmakuSpeedSetting.nextFocusLeftId=danmakuSetting.id
        danmakuSpeedSetting.nextFocusRightId=danmakuSetting.id
        danmakuScaleRow.nextFocusLeftId=danmakuScaleRow.id
        danmakuScaleRow.nextFocusRightId=danmakuScaleRow.id
        danmakuAlphaRow.nextFocusLeftId=danmakuAlphaRow.id
        danmakuAlphaRow.nextFocusRightId=danmakuAlphaRow.id
        // 主题网格首行：正上方就是这一栏的最后一行（弹幕透明度；隐藏档下是弹幕显示）。
        // 主题网格第一行的正上方是「按键设置」那一栏的最后一行（那一栏还没建好时退回弹幕栏）。
        TvTheme.entries.chunked(THEME_GRID_COLUMNS).firstOrNull().orEmpty().forEach { option ->
            themeRows[option]?.nextFocusUpId=keyTail
        }
        if(!shown && (danmakuSpeedSetting.hasFocus() || danmakuScaleRow.hasFocus() || danmakuAlphaRow.hasFocus())) danmakuSetting.requestFocus()
    }
    danmakuSetting.setOnClickListener { showDanmakuPicker(danmakuSetting,false) { syncDanmakuStyleRows() } }
    // —— 按键设置：整宽分组 + 2 × 3 的网格 + 「恢复默认按键」 ——
    // 六格排成两列三行（上 / 下、左 / 右、菜单 / 确定），比一行六个宽得多也短得多：
    // 每一格都要写出「键名 · 当前动作」，一屏宽塞六个只会全都变成省略号。
    //
    // 作用域是**播放界面**：浏览页面里上下左右就是焦点移动，是遥控器的基本手感，不能改；
    // 返回键也不在其中——它在任何绑定下都必须是退出播放的出口，否则用户可能把自己锁在播放界面里。
    // 这两条边界写在 [com.hgtv.star.core.KeyMap] 的注释里，改这里之前先读那一段。
    val keys=column().apply { clipChildren=false; clipToPadding=false }
    groupPanel(keys)
    parent.addView(keys,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
    heading(keys,"按键设置","播放界面：上下左右 / 菜单键 / 确定键各做什么，改完立刻生效")
    val keyLines=listOf(row(),row(),row()).onEachIndexed { index,line ->
        keys.addView(line,lp(-1,-2).apply { if(index>0) topMargin=dp(TvStyle.GAP_INLINE) })
    }
    /**
     * 六格 + 「恢复默认」那一行的文案：改完一个键就地刷新，不重建整页。
     *
     * 只改文字（[TvButton.setLabel]），不动视图树：焦点仍停在用户刚点的那一格上，
     * 视线不会因为一次重排而跳走（清晰度 / 倍速那几行也是这么做的）。
     *
     * 它写在 [keyCell] 前面：Kotlin 的局部函数不能前向引用，而每一格的点击回调要用它。
     */
    fun refreshKeyLabels() {
        keyCells.forEachIndexed { index,cell ->
            val label=keyRowLabel(PlayerKey.entries[index])
            cell.setLabel(label); cell.contentDescription=label
        }
        keyRestore?.setLabel(restoreKeysLabel())
    }
    /**
     * 一格按键：与上面两列同一个按钮（左对齐、等分一行两格），文案是「键名  ·  当前动作」。
     *
     * 点击回调在按钮建好之后才挂：它要引用按钮自己（弹窗关掉时把焦点还给这一格），
     * 与弹幕那一格是同一套写法（见 [gridSetting] 与上面的 `danmakuSetting.setOnClickListener`）。
     */
    fun keyCell(line: LinearLayout,key: PlayerKey): TvButton {
        val cell=gridSetting(line,"") {}
        cell.setOnClickListener { showKeyActionPicker(key,cell) { refreshKeyLabels() } }
        return cell
    }
    keyCells=PlayerKey.entries.mapIndexed { index,key -> keyCell(keyLines[index/KEY_GRID_COLUMNS],key) }
    keyCells.forEachIndexed { index,cell ->
        // 行内左右互指、行间上下互指，两端停在自己身上（与主题网格同一套规则）。
        cell.nextFocusLeftId=(keyCells.getOrNull(index-1)?.takeIf { index%KEY_GRID_COLUMNS!=0 } ?: cell).id
        cell.nextFocusRightId=(keyCells.getOrNull(index+1)?.takeIf { index%KEY_GRID_COLUMNS!=KEY_GRID_COLUMNS-1 } ?: cell).id
        cell.nextFocusUpId=(keyCells.getOrNull(index-KEY_GRID_COLUMNS) ?: cell).id
        cell.nextFocusDownId=(keyCells.getOrNull(index+KEY_GRID_COLUMNS) ?: cell).id
    }
    // 「恢复默认按键」：整栏宽的一行，改回出厂绑定（上 / 菜单 = 播放设置、下 = 选集、
    // 左 = 快退、右 = 快进、确定 = 播放 / 暂停）。文案本身就是读数，恢复之后它会写回「当前为默认」。
    val restore=setting(keys,"") { library.keyMap=KeyMap.DEFAULT; refreshKeyLabels() }
    keyRestore=restore
    restore.nextFocusLeftId=restore.id
    restore.nextFocusRightId=restore.id
    restore.nextFocusUpId=keyCells.lastOrNull()?.id ?: restore.id
    // 最后一行的两格按下都进「恢复默认」（下面那一行整栏宽，两格落到同一处）。
    keyCells.takeLast(KEY_GRID_COLUMNS).forEach { it.nextFocusDownId=restore.id }
    refreshKeyLabels()
    // —— 主题外观：整宽分组 + 一行 THEME_GRID_COLUMNS 个芯片 ——
    // 14 套材质排成 4 行，比「一行一个主题」省下三分之二的高度；
    // 芯片就是全站统一的按钮（紧凑档），选中态是「按进去」的凹槽，凹下去的那一枚就是当前主题。
    val appearance=column().apply { clipChildren=false; clipToPadding=false }
    groupPanel(appearance)
    parent.addView(appearance,lp(-1,-2).apply { topMargin=dp(TvStyle.GAP_GROUP) })
    heading(appearance,"主题外观","${TvTheme.entries.size} 套新拟态材质，选中即生效")
    val themeGrid=column().apply { clipChildren=false; clipToPadding=false }
    appearance.addView(themeGrid,lp(-1,-2))
    themeRows.clear()
    TvTheme.entries.chunked(THEME_GRID_COLUMNS).forEachIndexed { rowIndex, line ->
        val row=row().apply { if(rowIndex>0) setPadding(0,dp(TvStyle.GAP_INLINE),0,0) }
        line.forEachIndexed { index, option ->
            val chip=TvButton(this,option.label,ButtonSize.COMPACT,ButtonTone.SECONDARY,action={ switchTheme(option) })
            chip.setSelectedState(option==TvStyle.theme)
            chip.contentDescription=option.label+"，"+option.scene+if(option==TvStyle.theme) "，当前主题" else ""
            row.addView(chip,lp(0,-2).apply { weight=1f; if(index>0) leftMargin=dp(TvStyle.GAP_INLINE) })
            themeRows[option]=chip
        }
        // 最后一行不满时用空位占住列宽，芯片不会被拉成半屏宽。
        repeat(THEME_GRID_COLUMNS-line.size) { row.addView(Space(this),lp(0,1).apply { weight=1f }) }
        themeGrid.addView(row,lp(-1,-2))
    }
    appearance.addView(
        text("当前：${TvStyle.theme.label} · ${TvStyle.theme.scene}",13f,TvStyle.textDim)
            .apply { setPadding(0,dp(TvStyle.GAP_BLOCK),0,0) },
        lp(-1,-2)
    )
    // —— 焦点串联 ——
    // 上面两列：列内上下、列间左右按行号对齐（现在两列都是 4 项，行号一一对应）；
    // 末项按下进弹幕那一栏的第一行（左列接「弹幕显示」、右列接「弹幕速度」，与列号对齐）。
    // 弹幕栏内部的上下左右**全部**由 [syncDanmakuStyleRows] 按当前档位指：那三行只是隐藏档下
    // 不占位，链必须先在再按档位改，否则在弹窗里切回可见档之后这几行还是选不中
    //（早先按档位裁剪列内容的写法就栽在这里）。
    val playbackItems=listOf<View>(qualityButton,defaultSpeed,frame,autoNextButton)
    val deviceItems=listOf<View>(backup,hidden,clearCacheRow,versionRow,license)
    val columns=listOf(playbackItems,deviceItems)
    columns.forEachIndexed { column,items -> items.forEachIndexed { index,view ->
        view.nextFocusUpId=items.getOrNull(index-1)?.id ?: (navEntry()?.id ?: View.NO_ID)
        // 两列的末项（自动连播 / 开源许可）按下进弹幕那一栏的第一行，各接自己那一半。
        view.nextFocusDownId=items.getOrNull(index+1)?.id ?: (if(column==0) danmakuSetting.id else danmakuSpeedSetting.id)
    } }
    // 弹幕那一栏：第一行左格上行回到「自动连播」、右格上行回到「开源许可」（与上面两列的末项对接），
    // 栏内其余的上下左右出口由 [syncDanmakuStyleRows] 按当前档位指（隐藏时那三行不占位）。
    danmakuSetting.nextFocusUpId=autoNextButton.id
    danmakuSpeedSetting.nextFocusUpId=license.id
    columns.forEachIndexed { column,items -> items.forEachIndexed { index,view ->
        view.nextFocusLeftId=(columns.getOrNull(column-1)?.getOrNull(index) ?: view).id
        view.nextFocusRightId=(columns.getOrNull(column+1)?.getOrNull(index) ?: view).id
    } }
    // 主题网格：行内左右相连、行间上下相连，两端停住；第一行往上回到它正上方那一行，
    // 也就是弹幕那一栏的最后一行（可见档 → 「弹幕透明度」、隐藏档 → 「弹幕显示」，
    // 出口按档位现算，见 [syncDanmakuStyleRows]）。
    val themeLines=TvTheme.entries.mapNotNull { themeRows[it] }.chunked(THEME_GRID_COLUMNS)
    themeLines.forEachIndexed { rowIndex, line ->
        line.forEachIndexed { index, chip ->
            chip.nextFocusLeftId=line.getOrNull(index-1)?.id ?: chip.id
            chip.nextFocusRightId=line.getOrNull(index+1)?.id ?: chip.id
            // 第一行的上行出口交给 [syncDanmakuStyleRows] 按当前弹幕档位指过去
            // （整行都在弹幕那一栏下面，因此整行都回到它的最后一行）。
            chip.nextFocusUpId=themeLines.getOrNull(rowIndex-1)?.getOrNull(index)?.id ?: (keyRestore?.id ?: danmakuAlphaRow.id)
            chip.nextFocusDownId=themeLines.getOrNull(rowIndex+1)?.getOrNull(index)?.id ?: chip.id
        }
    }
    // 两列的末项按下都进弹幕栏（见上面的 columns 循环），这里不再单独指右半边。
    syncDanmakuStyleRows()
    navIcons().forEach { it.nextFocusDownId=qualityButton.id }
    parent.addView(text("红果TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(12),dp(20),0,dp(10)) })
    navEntry()?.requestFocus()
}
/**
 * 页面内提示（替代 Toast）。
 *
 * 系统 Toast 是独立于界面的一层浮窗，样式与字号不受本应用控制，电视上还会盖在画面正中；
 * 需要给一句「刚刚发生了什么」时改用它——页头下面一行淡字，不参与焦点、几秒后自己消失。
 */
internal fun MainActivity.showHint(message: String) {
    catalogHint=message
    catalogHintView?.let { view -> view.text=message; view.visibility=View.VISIBLE }
    main.removeCallbacks(hideHint)
    main.postDelayed(hideHint,4000)
}
/**
 * 「开源许可」弹窗正文。
 *
 * 信息按当前仓库写：派生来源、源码仓库、GPL-3.0-only 的许可范围、第三方来源
 * （对照 `NOTICE.md`），以及本机记录的边界。发布地址指向本仓库的 Releases，
 * 不再引用已经不存在的公开镜像仓库。
 *
 * **派生来源这一条与 `NOTICE.md` 第 1 节一一对应**：fork 关系、GPL-3.0 派生、
 * 自 2026-10-06 起在上游基础上修改、上游著作权归其作者。改 `NOTICE.md` 时同步改这里。
 */
internal fun MainActivity.licenseText(): String = listOf(
    "红果TV ${BuildConfig.VERSION_NAME} · GPL-3.0-only",
    "",
    "源码与许可证：github.com/pan8664716/HGTV",
    "  许可证全文见 LICENSE，第三方声明见 NOTICE.md",
    "",
    "派生自 N3urda/hongguoTV（GPL-3.0），自 2026-10-06 起在上游基础上修改；",
    "  上游著作权归其作者，逐项改动见 NOTICE.md 与 docs/CHANGELOG.md。",
    "",
    "内容协议与加密处理移植自 drpys（commit 22261ad，GPL-3.0）。",
    "",
    "第三方组件（完整清单见 NOTICE.md）：",
    "  Kotlin / AndroidX Media3 / OkHttp / ZXing：Apache-2.0",
    "  Bouncy Castle / jsoup / Microsoft Fluent Emoji：MIT",
    "  desugar_jdk_libs：GPL-2.0 with Classpath Exception",
    "",
    "本机记录（收藏 / 观看进度 / 设置）只保存在这台设备上，不上传。"
).joinToString("\n")
/**
 * 搜索：**不分内容类型**（顶部那两个「漫剧 / 短剧」开关已经去掉）。
 * 漫剧与短剧 / 真人剧的搜索结果合并去重后一起显示，由 [ContentRepository.search] 负责。
 */
/**
 * 搜索页的搜索控件：**整条都在导航栏里**（搜索框 + 搜索 / 历史）。
 *
 * 用户从别的页面按「搜索」图标进来就是要打字，输入框再占页面第一条会白花一屏的高度；
 * 放进导航栏之后页面第一行就是提示词 / 热搜词，输入框与它上下相邻，方向键一步就到。
 *
 * 输入框吃掉导航栏中间的弹性空间（`weight=1`），因此它总是这一行里最宽的一件；
 * 右边两个按钮按内容宽度排，圆形入口跟在最后。
 */
