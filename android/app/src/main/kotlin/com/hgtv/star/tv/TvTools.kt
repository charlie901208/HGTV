// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.tv

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hgtv.star.core.AccountCredentialsParser
import com.hgtv.star.core.AccountImportServer
import com.hgtv.star.core.PhoneSearchServer
import com.hgtv.star.core.PhoneLogin
import com.hgtv.star.core.LibraryTransferServer
import com.hgtv.star.core.BackupData
import java.net.Inet4Address
import java.util.concurrent.Executors

/** 一条提示显示多久（毫秒）。短是刻意的：它只用来交代「刚才那一下失败了」。 */
private const val TOAST_MS=1900L
/** 提示淡入的时长（毫秒）。 */
private const val TOAST_FADE=120L
/** 提示距屏幕底边的距离（dp）。 */
private const val TOAST_MARGIN=34

/** 手填 sessionid 那个输入框的可见高度（与常规按钮同档，比默认的一行高出一倍）。 */
private const val SESSION_FIELD_HEIGHT=TvStyle.BUTTON_HERO
/** 手填 sessionid 的正文 / 提示字号。 */
private const val SESSION_FIELD_TEXT=20f

/**
 * 原生模态交互：手机输入、备份恢复、二次确认、列表选择。
 *
 * 全部走 [TvDialog]，因此弹窗里的按钮与页面上是同一套尺寸、圆角与焦点反馈——
 * 这里不再出现系统 AlertDialog 的 Material 文字按钮。
 */
class TvTools(private val activity: Activity) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var dialog: TvDialog?=null
    val showing: Boolean get()=dialog?.isShowing==true
    private var phone: java.io.Closeable?=null
    private var toastView: TextView?=null
    private val hideToast=Runnable {
        val view=toastView ?: return@Runnable
        toastView=null
        (view.parent as? android.view.ViewGroup)?.removeView(view)
    }
    private fun dp(value: Int)=(value*activity.resources.displayMetrics.density).toInt()
    /** 弹窗正文文字：与全站正文同一档字号与颜色，不再单独写一套白色 + 内边距。 */
    private fun label(value: String,size: Float=16f)=activity.tvText(value,size,TvStyle.textDim)
    private fun column()=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
    private fun open(next: TvDialog,anchor: View?) {
        dialog?.dismiss(); dialog=next
        next.show(anchor) { if(dialog===next) { phone?.close(); phone=null; dialog=null } }
    }
    fun close() { dialog?.dismiss(); phone?.close(); phone=null }
    fun destroy() { close(); clearToast(); worker.shutdownNow() }
    /**
     * 轻量提示（短时浮层）。
     *
     * 用在「需要登录态的请求报错了」这类**必须让用户知道、又不值得打断操作**的场合：
     * 每条只显示 [TOAST_MS]，新的那条**立刻顶掉**旧的那条（不排队、不叠加），
     * 因此连续几次失败不会在屏幕上堆一串提示。
     *
     * 它不参与焦点、不可点击、也不吃按键：电视上没有系统 Toast 那一层浮窗，
     * 样式与字号仍然由本应用说了算（与 [MainActivity.showHint] 的分工是：
     * 那一行属于页面内的状态说明，这一层属于「刚才那一下失败了」的即时反馈）。
     */
    fun toast(message: String) {
        if(message.isBlank()) return
        val root=activity.window?.decorView as? android.view.ViewGroup ?: return
        clearToast()
        val view=TextView(activity).apply {
            text=message
            textSize=15f
            setTextColor(TvStyle.text)
            maxLines=2
            setPadding(dp(20),dp(11),dp(20),dp(11))
            background=TvStyle.soft(activity,TvStyle.surfaceHigh,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.RAISED,depth=1.15f)
            // 不吃焦点、不吃按键：提示出现时用户正在按的那一下不能被打断。
            isFocusable=false
            isFocusableInTouchMode=false
            isClickable=false
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            alpha=0f
        }
        root.addView(
            view,
            FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin=dp(TOAST_MARGIN) }
        )
        toastView=view
        view.animate().alpha(1f).setDuration(TOAST_FADE).start()
        main.postDelayed(hideToast,TOAST_MS)
    }
    private fun clearToast() {
        main.removeCallbacks(hideToast)
        val view=toastView ?: return
        toastView=null
        view.animate().cancel()
        (view.parent as? android.view.ViewGroup)?.removeView(view)
    }
    fun info(title: String,message: String,anchor: View?) {
        val next=TvDialog(activity,title).message(message)
        next.button("知道了")
        open(next,anchor)
    }
    /**
     * 二次确认：焦点默认落在「取消」上，确认必须多按一次，误触不会造成破坏。
     *
     * 两个按钮都用常规档（**不用 PRIMARY**）：PRIMARY 是「常亮的亮面板」，
     * 在一排按钮里看起来就像「一直处于焦点」，退出确认这类弹窗里会让人以为焦点已经落在确认上。
     * 弹窗里靠文案区分动作就够了，按钮外观一律常态。
     */
    fun confirm(title: String,message: String,anchor: View?,action: ()->Unit) {
        val next=TvDialog(activity,title).message(message)
        next.button("取消") {}
        next.button("确认") { action() }
        open(next,anchor)
    }
    fun choose(title: String,labels: List<String>,anchor: View?,onChoice: (Int)->Unit,extra: String?=null,onExtra: ()->Unit={}) {
        val next=TvDialog(activity,title).choices(labels,onChoice=onChoice)
        if(extra!=null) next.button(extra) { onExtra() }
        next.button("取消") {}
        open(next,anchor)
    }
    /**
     * 二维码：一块嵌在弹窗里的白色凸起方板。
     * 新拟态里「白」是最亮的一档材质，因此二维码不用描边也压得住深色弹窗，
     * 四周的柔光带同时也是二维码的静区（quiet zone），扫起来更稳。
     */
    private fun qr(url: String): FrameLayout {
        val bits=QRCodeWriter().encode(url,BarcodeFormat.QR_CODE,360,360)
        val pixels=IntArray(360*360) { i->if(bits[i%360,i/360]) Color.BLACK else Color.WHITE }
        val code=ImageView(activity).apply {
            setImageBitmap(Bitmap.createBitmap(pixels,360,360,Bitmap.Config.ARGB_8888))
            contentDescription="二维码"
        }
        val inset=dp(TvStyle.SOFT_BAND)+dp(TvStyle.GAP_TIGHT)
        return FrameLayout(activity).apply {
            background=TvStyle.soft(activity,Color.WHITE,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND,SoftStyle.RAISED,depth=1.15f)
            setPadding(inset,inset,inset,inset)
            addView(code,FrameLayout.LayoutParams(-1,-1))
        }
    }
    /**
     * 电视在局域网里的 IPv4 地址：手机表单（手机输入 / 登录态 / 备份恢复）都要用它拼二维码。
     * 没有可用的 Wi-Fi 或有线网络时返回 null，由调用方给出各自的提示。
     */
    private fun lanAddress(): String? {
        val connectivity=activity.getSystemService(ConnectivityManager::class.java)
        val network=connectivity.activeNetwork
        val capabilities=connectivity.getNetworkCapabilities(network) ?: return null
        if(!(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return null
        return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }
    /** 二维码 + 说明文字的通用正文：两处扫码弹窗共用同一份排版与间距。 */
    private fun scanBody(instructions: String,url: String,into: LinearLayout) {
        val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        val side=dp(185+2*(TvStyle.GAP_TIGHT+TvStyle.SOFT_BAND))
        row.addView(qr(url),LinearLayout.LayoutParams(side,side))
        // 说明文字放进一条凹陷槽：与二维码的凸起方板形成一凹一凸，弹窗里两块内容一眼分得开。
        val band=dp(TvStyle.SOFT_BAND)
        val text=column().apply {
            background=TvStyle.well(activity,TvStyle.well,TvStyle.RADIUS_CARD,TvStyle.SOFT_BAND)
            setPadding(band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_INLINE),band+dp(TvStyle.GAP_INLINE))
        }
        text.addView(label(instructions,16f))
        text.addView(label(url,12f),LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(TvStyle.GAP_TIGHT) })
        row.addView(text,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(TvStyle.GAP_BLOCK) })
        into.addView(row)
    }
    fun phoneInput(anchor: View?,submit: (String)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("手机输入","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机输入剧名")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=PhoneSearchServer(address,{ query -> main.post { if(dialog===next) { next.dismiss(); submit(query) } } },{ main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("手机扫码后用浏览器打开，输入剧名发送到电视。\n需连接同一局域网；关闭窗口即失效，最长 5 分钟。",session.url,next.content)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启手机输入，请检查网络后重试。" } }
        }
    }
    /**
     * 搜索登录态（可选）：说明 + 手机填写 + 清除。
     *
     * 不填也能搜索：搜索会退回官网搜索 + 片库目录补页。填上 App 抓到的 `x-tt-token` 后，
     * 搜索直接使用 App 搜索接口（按 tab 分开取、可继续翻页）。口令在手机上粘贴。
     */
    fun searchSession(anchor: View?,current: String,save: (String)->Unit,clear: ()->Unit) {
        val next=TvDialog(activity,"搜索登录态")
        next.message("可选。不填也能搜索：搜索会退回官网搜索（第 1 页约 10 条），第 2 页起从片库目录里补。\n\n填上 App 抓到的 x-tt-token 后，搜索直接使用 App 搜索接口：一次 20 条、可以一直往下翻（两个 tab 的结果合并去重后一起显示）。\n\n口令只保存在本机、只用于搜索请求，不进备份文件。")
        next.button("取消") {}
        if(current.isNotBlank()) next.button("清除口令") { clear() }
        next.button("手机填写") { sessionToken(anchor) { save(it) } }
        open(next,anchor)
    }
    /**
     * 手机填写搜索登录态：与「手机输入」共用同一份一次性局域网表单。
     * 口令是上百个字符的串，在手机上粘贴比用遥控器一个字一个字敲快得多。
     */
    fun sessionToken(anchor: View?,save: (String)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("搜索登录态","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"填写搜索登录态")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=PhoneSearchServer(address,{ token -> main.post { if(dialog===next) { next.dismiss(); save(token) } } },{ main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } },form=PhoneSearchServer.Form.sessionToken)
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("手机扫码后用浏览器打开，粘贴 App 抓到的 x-tt-token（整段 Cookie 也可以）。\n只用于搜索请求；需连接同一局域网，关闭窗口即失效，最长 5 分钟。",session.url,next.content)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启手机填写，请检查网络后重试。" } }
        }
    }
    /**
     * 导入账号参数：手机扫码打开一个一次性页面，上传 App 的 `cookieStore.xml` 或粘贴整段 Cookie。
     *
     * 与「手机备份与恢复」同一套局域网约束（随机地址、限时、用完即关），
     * 区别是这里收的是**凭据文本**，由调用方解析与校验（见 `MainActivity.importAccount`）。
     */
    fun accountImport(anchor: View?,onImport: (String)->Unit,onLocalFile: ()->Unit) {
        // 先让用户挑方式：本机文件（电视上已存了 cookieStore.xml）、手机扫码上传，
        // 或者直接手填 sessionid。三者最终都归一到「一段文本」，
        // 由调用方解析（见 `MainActivity.parseCredentials`）。
        val pick=TvDialog(activity,"导入账号参数")
        pick.message("需要红果 App 的 cookieStore.xml，或者整段 Cookie；也可以只填 sessionid。\n\n三种方式：\n· 本机文件 —— 电视上已经有这个文件时用（U 盘 / 文件管理应用放进来的）；\n· 手机扫码 —— 手机与电视连同一局域网，用手机把文件或口令传过来；\n· 输入 sessionid —— 只拿到那一串时直接手填（其余字段平台不校验）。")
        pick.button("取消") {}
        pick.button("手机扫码") { phoneUpload(anchor,onImport) }
        pick.button("本机文件") { onLocalFile() }
        // 弹窗里所有按钮都用常态档：PRIMARY 是「常亮的亮面板」，在一排按钮里看起来
        // 就像「一直处于焦点」——这一行有四个按钮，突出其中一个只会让人误以为焦点已经在它上面。
        pick.button("输入 sessionid") { sessionIdEntry(anchor,onImport) }
        open(pick,anchor)
    }
    /**
     * 手填 `sessionid`。
     *
     * 单独留这一条路是因为**实测服务端只认 `sessionid`**（其余 13 个字段加上去结果一样，
     * 见 `AccountCredentialsParser.COOKIE_KEYS` 的注释），所以只要用户能拿到那一串就够了——
     * 不用去翻 `cookieStore.xml`。
     *
     * 输入框复用搜索页那一套外观（凹槽 + 胶囊 + 焦点加深），并且**只收 `sessionid` 的值**：
     * 粘贴整段 Cookie 或整行 `sessionid=…` 也认（[normalizeSessionId] 会剥掉前缀与空白）。
     */
    private fun sessionIdEntry(anchor: View?,onImport: (String)->Unit) {
        val next=TvDialog(activity,"输入 sessionid")
        next.message("填红果 App 登录态里的 sessionid：**32 位十六进制**，形如 cf98cf3cd41f0b56188dd3f2226a10c4。\n\n" +
            "在 cookieStore.xml 里搜 sessionid 会看到 19 条同名的（不同网址下各一份），值都一样，挑任意一条即可——" +
            "但**不要粘那一长串十六进制**（那是 App 的序列化数据）。\n" +
            "整行 <string …>、整段 Cookie、或直接填那 32 位，这三种都认。")
        // 输入框占满整行、高度与常规按钮同档（56dp + 柔光带）：只在正文里放一行
        // `wrap_content` 的 EditText 会被压成一条细槽，字都挤在里面。
        val input=sessionIdField()
        next.content.addView(input,LinearLayout.LayoutParams(-1,dp(SESSION_FIELD_HEIGHT+2*TvStyle.SOFT_BAND))
            .apply { topMargin=dp(TvStyle.GAP_BLOCK) })
        // 输入框下面一行小字：把「只用填这一项」这件事说清楚，省得用户去找整个文件。
        next.content.addView(
            label("服务端只校验这一项，填对就能显示账号信息。",12f),
            LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_TIGHT) }
        )
        val cancel=next.button("取消") {}
        // 同上：弹窗里按钮一律常态。`keepOpen` 让它点完不关（校验失败时还要留在框里）。
        val submit=next.button("导入",keepOpen=true) {
            val value=normalizeSessionId(input.text.toString())
            if(value.isEmpty()) { input.error="请填 sessionid"; return@button }
            next.dismiss()
            onImport(value)
        }
        // 焦点链：输入框按下键直接到「导入」，两个按钮按上键都回输入框。
        // 弹窗只在内容区溢出时才自动接线（见 `TvDialog.wireContentFocus`），这一页内容不长，
        // 因此这两条要自己设，否则焦点会困在输入框里出不来。
        input.nextFocusDownId=submit.id
        cancel.nextFocusUpId=input.id
        submit.nextFocusUpId=input.id
        // 焦点交给 [TvDialog] 自己的规则：**第一枚按钮**（也就是「取消」）。
        // 不主动把焦点塞进输入框——那样一打开弹窗就在输入框里闪光标，
        // 用户按左右键还会被输入框吃掉，想按「取消」得先往下走。
        open(next,anchor)
    }
    /**
     * 手机号登录第一步：选地区。常用的四个直接给按钮（[onPick] 收到区号），
     * 其余地区选「其他地区」（收到 null），下一步自己填「+区号 号码」。
     */
    fun phoneRegion(anchor: View?,onPick: (String?)->Unit) {
        val pick=TvDialog(activity,"手机号登录")
        pick.message("选择手机号所属地区，验证码会以短信发送。\n支持境外号码；不在列表里的地区选「其他地区」。")
        pick.button("+86 大陆") { onPick("86") }
        pick.button("+886 台湾") { onPick("886") }
        pick.button("+852 香港") { onPick("852") }
        pick.button("其他地区") { onPick(null) }
        open(pick,anchor)
    }
    /** 填手机号。[countryCode] 为 null 时整串填「+区号 号码」；[error] 是上一次发码失败的原因。 */
    fun phoneNumber(anchor: View?,countryCode: String?,error: String?,onSubmit: (String)->Unit) {
        val prompt=if(countryCode==null) "填「+区号 号码」，区号与号码之间留一个空格，例如 +853 61234567。"
            else "地区 +$countryCode。填手机号，开头的 0 可留可省。"
        entry(anchor,"输入手机号",prompt,if(countryCode==null) "+区号 号码" else "手机号码",
            android.text.InputType.TYPE_CLASS_PHONE,"发送验证码",error,
            { raw -> runCatching { if(countryCode==null) PhoneLogin.parse(raw) else PhoneLogin.format(countryCode,raw) } },onSubmit)
    }
    /** 填验证码。[error] 是上一次登录失败的原因。 */
    fun phoneCode(anchor: View?,sentTo: String,error: String?,onSubmit: (String)->Unit) {
        entry(anchor,"输入验证码","验证码已发到 $sentTo，收到短信后填入。\n要换号码或重发，按返回键从头开始。","短信验证码",
            android.text.InputType.TYPE_CLASS_NUMBER,"登录",error,
            { raw -> raw.filter(Char::isDigit).let { code -> if(code.length in 4..8) Result.success(code) else Result.failure(IllegalArgumentException("验证码是 4~8 位数字")) } },onSubmit)
    }
    /**
     * 单行输入弹窗（与「输入 sessionid」同一套外观与焦点链）。
     * [validate] 失败就把原因标在输入框上、弹窗不关；成功先关窗再交给 [onSubmit]（它可能接着开下一个弹窗）。
     */
    private fun entry(anchor: View?,title: String,prompt: String,hint: String,type: Int,action: String,error: String?,
                      validate: (String)->Result<String>,onSubmit: (String)->Unit) {
        val next=TvDialog(activity,title)
        next.message(prompt)
        val input=sessionIdField().apply { this.hint=hint; inputType=type }
        next.content.addView(input,LinearLayout.LayoutParams(-1,dp(SESSION_FIELD_HEIGHT+2*TvStyle.SOFT_BAND))
            .apply { topMargin=dp(TvStyle.GAP_BLOCK) })
        error?.let {
            next.content.addView(label("上次失败：$it",13f),LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_TIGHT) })
        }
        val cancel=next.button("取消") {}
        val submit=next.button(action,keepOpen=true) {
            validate(input.text.toString())
                .onSuccess { value -> next.dismiss(); onSubmit(value) }
                .onFailure { input.error=it.message }
        }
        input.nextFocusDownId=submit.id
        cancel.nextFocusUpId=input.id
        submit.nextFocusUpId=input.id
        open(next,anchor)
    }
    /** 手填 sessionid 的输入框：外观与搜索页那一条一致（凹陷槽 + 胶囊圆角 + 焦点加深）。 */
    private fun sessionIdField(): EditText {
        val band=dp(TvStyle.SOFT_BAND)
        val view=EditText(activity).apply {
            id=View.generateViewId()
            hint="在此粘贴 sessionid，或整段 Cookie"
            // 字号比按钮正文大一档（20sp）：这一行是弹窗里唯一要读的东西，
            // 32 位十六进制串在小字下很难核对。
            textSize=SESSION_FIELD_TEXT
            setTextColor(TvStyle.text); setHintTextColor(TvStyle.muted)
            isSingleLine=true; maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.START
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters=arrayOf(android.text.InputFilter.LengthFilter(512))
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            includeFontPadding=false
            // 内边距按常规档按钮（28dp）+ 柔光带：槽里左右各留出投影的位置，文字才不会贴着边。
            setPadding(dp(TvStyle.BUTTON_PAD_NORMAL)+band,band,dp(TvStyle.BUTTON_PAD_NORMAL)+band,band)
            background=TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.INSET,ring=TvStyle.outline,ringWidth=1)
            setOnFocusChangeListener { focused,_ ->
                val on=focused.hasFocus()
                focused.background=TvStyle.soft(activity,TvStyle.well,TvStyle.RADIUS_PILL,TvStyle.SOFT_BAND,SoftStyle.INSET,
                    ring=if(on) TvStyle.focusLine else TvStyle.outline,ringWidth=1,depth=if(on) 1.6f else 1.1f)
            }
        }
        return view
    }
    /**
     * 把用户填的东西归一成**纯 sessionid 值**。
     *
     * 实际归一交给 [AccountCredentialsParser.sessionIdOf]（它认得整行 `<string>`、
     * 十六进制序列化流、整段 Cookie 与纯值），这里只做一次形状检查——
     * 用户最常踩的坑是把 `cookieStore.xml` 里那一长串十六进制粘进来，
     * 那会变成一个**非法 sessionid**：平台不报错，只把请求降级成匿名设备。
     */
    private fun normalizeSessionId(raw: String): String {
        val value=AccountCredentialsParser.sessionIdOf(raw)
        return if(AccountCredentialsParser.looksLikeSessionId(value)) value else ""
    }
    /** 手机扫码上传：一次性局域网页面，收 `cookieStore.xml` 或整段 Cookie。 */
    private fun phoneUpload(anchor: View?,onImport: (String)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("导入账号参数","请让电视和手机连接同一局域网（Wi-Fi 或有线网络）后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机上传账号参数")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=AccountImportServer(address,{ text -> main.post { if(dialog===next) { next.dismiss(); onImport(text) } } },
                    { main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("手机扫码后用浏览器打开，上传红果 App 的 cookieStore.xml，或粘贴整段 Cookie。\n只保存在电视本机；需连接同一局域网，关闭窗口即失效，最长 5 分钟。",session.url,next.content)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启上传页面，请检查网络后重试。" } }
        }
    }
    fun libraryTransfer(backup: String,anchor: View?,restore: (BackupData,Boolean)->Unit) {
        val address=lanAddress()
        if(address==null) {
            info("备份与恢复","请让电视和手机连接同一局域网后重试。",anchor); return
        }
        val next=TvDialog(activity,"手机备份与恢复")
        next.button("关闭")
        val status=label("正在生成二维码…"); next.content.addView(status)
        open(next,anchor)
        worker.execute {
            try {
                val session=LibraryTransferServer(address,backup,{ data -> main.post {
                    if(dialog!==next) return@post
                    next.dismiss()
                    val confirm=TvDialog(activity,"确认恢复本机记录")
                    confirm.message("备份含 ${data.favorites.size} 部收藏、${data.history.size} 条观看记录、${data.searches.size} 条搜索、${data.later.size} 部稍后观看、${data.hidden.size} 部已隐藏的剧集。\n\n收藏合并，观看位置以较新的记录为准；已有收藏优先保留（最多 500 部），历史保留最近 200 条。")
                    // 开关用全站统一的按钮：选中态是「按进去」的凹槽（不带颜色），
                    // 系统 CheckBox 的勾选色是自带的一套，与「选框不带颜色」的规则冲突。
                    var restoreSettings=false
                    lateinit var settings: ToggleChip
                    settings=ToggleChip(activity,"同时恢复播放设置和内容分类 · 关",false) {
                        restoreSettings=!restoreSettings
                        settings.setActive(restoreSettings)
                        settings.setLabel("同时恢复播放设置和内容分类 · ${if(restoreSettings) "开" else "关"}")
                    }
                    confirm.content.addView(settings,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(TvStyle.GAP_BLOCK) })
                    confirm.button("取消") {}
                    confirm.button("合并恢复") { restore(data,restoreSettings) }
                    open(confirm,anchor)
                } },{ main.post { if(dialog===next) { next.content.removeAllViews(); next.content.addView(label("二维码已过期，请关闭后重新打开。")) } } })
                main.post {
                    if(dialog!==next) { session.close(); return@post }
                    phone=session; next.content.removeAllViews()
                    scanBody("手机扫码，用浏览器下载备份或选择备份文件恢复。\n仅限同一局域网；关闭即失效，最长 5 分钟。恢复需在电视确认。",session.url,next.content)
                }
            } catch(_: Exception) { main.post { if(dialog===next) status.text="无法开启备份传输，请检查网络后重试。" } }
        }
    }
}
