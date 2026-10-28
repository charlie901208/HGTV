// SPDX-License-Identifier: GPL-3.0-only
package com.hgtv.star.core

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.json.JSONArray
import java.io.IOException
import java.util.concurrent.TimeUnit

internal fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.str(key: String) = if (isNull(key)) "" else optString(key, "").trim()
internal fun cleanUrl(s: String) = s.replace("\\/","/").replace("\\u002F","/",ignoreCase=true).replace("\\u0026","&").replace("&amp;","&")
data class Series(val id: String, val title: String, val cover: String = "", val description: String = "", val badge: String = "", val tags: String = "") {
    fun toJson() = JSONObject().put("id",id).put("title",title).put("cover",cover).put("description",description).put("badge",badge).put("tags",tags)
    companion object { fun fromJson(o: JSONObject) = Series(o.str("id"),o.str("title"),o.str("cover"),o.str("description"),o.str("badge"),o.str("tags")) }
}
data class Detail(val series: Series, val episodes: List<String>)

/**
 * 简介页下方那一栏的内容：同系列各季在前（按季号升序），其余相关作品在后，已去重。
 *
 * **不随详情一起返回**：那一栏要额外发一次 App 请求，而详情页每次进都要开一次，
 * 把它挂在详情上会让「进简介页」变慢。界面在详情上屏之后后台预取（见 `prefetchRelated`）。
 */
data class RelatedWorks(
    val items: List<Series>,
    /** 其中属于**同一部剧**的那些季（按季号升序，含正在看的这一季）：界面据此标注「第 N 季」。 */
    val seasons: List<Series>
)
/**
 * 一条搜索提示词（联想词）。
 *
 * [series] 非空表示这条提示词**本身就是一部剧**（服务端给了 `video_data.series_id`）：
 * 点它可以跳过搜索直接进详情；为空时它只是一个查询词（`common_query` / `title` / `album` /
 * 分类这类），点它就拿 [name] 去搜。
 */
data class SearchSuggestion(
    val name: String,
    val series: Series? = null,
    /** 服务端的 `word_type`：`short_play_name` / `title` / `common_query` / `album` / `category` 等。 */
    val wordType: String = ""
)

data class RankPosition(val rank: Int?, val heat: String)
data class ComicRanking(val positions: Map<String,RankPosition>, val totalPages: Int, val updatedText: String)
data class CatalogPage(val items: List<Series>, val hasMore: Boolean, val ranking: ComicRanking? = null)
data class StreamInfo(val url: String, val key: ByteArray?, val quality: String)
class SearchSessionExpiredException: IOException("搜索结果已过期，请从第 1 页重新搜索")
/**
 * 这个请求需要登录态，而本机还没有可用的凭据（或凭据已失效）。
 *
 * 账号侧接口（收藏 / 历史 / 点赞 / 预约 / 资料）与带登录态的搜索都要求先导入
 * `sessionid`（见 [AccountCredentials]）。未导入时它们**一律不可用**：
 * 平台对匿名设备要么回空响应体、要么把请求降级成匿名账号，两者都会让界面显示
 * 「一条记录都没有」而不是「你还没导入」——因此统一抛这个异常，由界面翻成提示。
 */
class SignInRequiredException(message: String = "需要先导入账号参数（sessionid）"): IOException(message)

class ContentRepository(val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).callTimeout(45,TimeUnit.SECONDS).build(), private val clock: () -> Long = System::currentTimeMillis) {
    private val signer = Signer()
    private val site = "https://hongguoduanju.com"
    /**
     * 提示词接口要求的应用号（缺了它服务端返回 500）。
     * 8662 就是「红果短剧」在番茄系里的 aid（与 [VendorConstants.device] 里的 `aid` 同值）。
     */
    private val SUGGEST_APP_ID = "8662"
    /** 账号接口的域名。与搜索 / 弹幕同一套（[appValues] 的设备参数对这几个域名都认）。 */
    private val ACCOUNT_HOST = "api5-normal-sinfonlinea.fqnovel.com"
    /** 账号接口空响应体的重试次数。 */
    private val ACCOUNT_RETRIES = 4
    /** 写接口里「短剧」的 `book_type`（App 的 `ReadingBookType.Watch`）。 */
    private val BOOK_TYPE_WATCH = 2
    /** 写接口里「加入追剧」的操作码（App 的 `VideoShelfOperateType.AddShelf`）。 */
    private val VIDEO_SHELF_ADD_SHELF = 0
    /** 写接口里「取消追剧」的操作码（App 的 `VideoShelfOperateType.DelShelf`）。 */
    private val VIDEO_SHELF_DEL_SHELF = 1
    /**
     * 写接口的诊断日志出口。
     *
     * `core` 是纯 Kotlin 模块（没有 Android SDK），所以不能直接 `Log.i`；
     * 界面层启动时把它接到 `android.util.Log`（见 `MainActivity`），不接就是空操作。
     */
    var writeLog: (String) -> Unit = {}
    /**
     * 写接口被风控（`101144 HIT_SHARK_RULE`）时的重试次数与退避间隔（毫秒）。
     *
     * 风控窗口通常是**秒级到十几秒**：实测同一条请求隔 1s 重发还是被挡、隔十几秒就能过，
     * 因此退避必须拉开（早期写的 400/1000/1600ms 全落在同一个窗口里，等于没重试）。
     */
    private val ACCOUNT_WRITE_ATTEMPTS = 4
    private val ACCOUNT_WRITE_BACKOFF = longArrayOf(1_200L,4_000L,12_000L)
    /**
     * 一次 `multi-detail` 最多带多少个 id。
     *
     * 实测 50 个 id 一次能全部回（0.6s），因此按历史条数上限取 50：
     * 取小了（曾经是 20）会让第 21 条起只剩 `series_id`、没有片名与封面，
     * 界面上就是一排**空框**——这正是「历史里很多框不显示内容」的原因。
     */
    private val MULTI_DETAIL_LIMIT = 50
    /** 账号那七个请求共用的线程池：它们彼此独立，串行发要两三秒。 */
    private val accountPool=java.util.concurrent.Executors.newFixedThreadPool(6) { task ->
        Thread(task,"hgtv-account").apply { isDaemon=true }
    }
    /**
     * 提示词接口的 `web_id`：官网上是每次页面加载生成的一个随机标识。
     * 这里整个进程生成一次即可（服务端并不校验它与会话的对应关系）。
     */
    private val webId = java.util.UUID.randomUUID().toString()
    /**
     * 热搜词的种子词（见 [hotSearches]）：都是题材向的泛词，联想接口对它们返回的候选里
     * 混着一批真正的查询词（`30集短剧` / `都市逆袭` / `甜宠` 这类）。
     * 只取前三个：每个种子一次请求，串行约 200ms，再多就只是把同一批词换几个说法。
     */
    private val SUGGEST_SEEDS = listOf("短剧","都市","逆袭","甜宠","战神")
    /**
     * 热搜词的词类型优先级（见 [hotSearches]）。
     *
     * 题材 / 分类这类（`short_play_category` / `category`）读起来最像「大家在搜的题材」，
     * 排最前；`common_query` 是平台自己的搜索建议（`短剧最热榜前十名` 这种）排其次；
     * `title` / `album` 更像是书名 / 专辑名，放最后。
     */
    private val HOT_WORD_ORDER = listOf("short_play_category","category","common_query","title","album")
    /**
     * 热搜词的并发池：种子词彼此独立，串行五个要 1.8s，并发之后是单次请求的时间。
     * 池子很小且只在取热搜词时用一下，因此按需创建、线程是守护线程（不挡进程退出）。
     */
    private val SUGGEST_POOL = java.util.concurrent.Executors.newFixedThreadPool(SUGGEST_SEEDS.size) { runnable ->
        Thread(runnable,"hgtv-hotword").apply { isDaemon=true }
    }
    /** 热搜词的兜底表：提示接口整体不可用时用（搜索页不能空着）。 */
    private val FALLBACK_HOT = listOf(
        "短剧最热榜前十名","AI短剧","都市逆袭","甜宠","战神归来","穿越剧大全",
        "萌宝助攻","玄幻修仙系列","悬疑","古装","逆袭人生","豪门"
    )
    private data class DanmakuPage(val items: List<Danmaku>,val nextStart: Long,val cursor: String,val hasMore: Boolean,
                                   val raw: List<org.json.JSONObject> = emptyList())
    private data class SearchCursor(val searchId: String, val passback: String, val offset: Int?, val updatedAt: Long)
    private val searchCursors=linkedMapOf<Triple<ContentType,String,Int>,SearchCursor>()
    /**
     * 搜索已经给过界面的剧集 id（按「类型 + 关键词」分组）。
     *
     * 官网搜索与片库目录会重复给出同一部剧（同一部剧的不同季、目录分页本身也会重复），
     * 这里替界面记住已经发过的条目，后面补页时只回新片名；
     * 一组补页里一个新条目都没有，就说明这个关键词在片库里已经找完了。
     */
    private val catalogSeen=linkedMapOf<String,MutableSet<String>>()
    private fun catalogKey(types: List<ContentType>,keyword: String)="${types.joinToString("+") { it.storedValue }}:$keyword"
    /**
     * App 搜索接口还能不能用：空响应体说明平台对匿名设备已经不给数据了（实测所有线路、多组设备参数都一样）。
     * null = 还没试过。只有确认它「活着或未知」时才按游标过期处理漫剧翻页，
     * 否则漫剧搜索会卡在第 1 页，永远走不到官网搜索 + 片库目录补页那条路。
     */
    @Volatile private var appSearchAlive: Boolean?=null
    /**
     * 搜索登录态（可选）：App 搜索接口对匿名设备返回空响应体，带上 App 抓到的 `x-tt-token`
     * 后它会按该账号返回数据（第 1 页起就是完整结果，不再依赖官网搜索与片库目录）。
     * 留空时按匿名设备请求，搜索仍然可用——退回官网搜索 + 片库目录补页。
     */
    @Volatile var sessionToken: String?=null
    /**
     * 账号凭据（个人信息页导入的整套登录态）。
     *
     * 与 [sessionToken] 的分工：`sessionToken` 是历史遗留的「只给搜索用的口令」，
     * 这里这份带设备档案，账号接口（收藏 / 历史 / 点赞 / 预约 / 资料）与 App 搜索都用它。
     * 两处都配了时以这份为准（见 [withSession]）。
     */
    @Volatile var credentials: AccountCredentials?=null
        set(value) {
            // 登录态一换，「App 搜索还能不能用」这个判断就得重新做：
            // 匿名时接口回空 body（被判死），配上凭据之后它是能出数据的。
            if(field?.cookie!=value?.cookie) appSearchAlive=null
            field=value
            // 签名里的 x-medusa 要上报设备档案（机型 / 系统版本 / cdid …），
            // 与凭据一起换掉，免得拿着 A 设备的档案去发 B 设备的写请求。
            // 档案是三层合并的结果（见 [deviceProfile]）：内置 → 本机身份 → 凭据的真机档案。
            signer.updateDevice(deviceProfile())
        }
    /**
     * 本机设备身份（`device_id` / `iid`），由界面层注入（见 `Library.deviceIdentity`）。
     *
     * **这份身份必须是平台注册过的**（[registerDevice] 的下发结果）：内容接口按
     * 「服务端注册过的 `device_id` + `install_id` 这一对」放行，随便编一对数字
     * （哪怕形状完全正确）都拿不到数据——App 搜索回 `200 + 0 字节`、播放回
     * `101000 服务异常`。注册是匿名的，见 [DeviceRegister]。
     *
     * 内置档案里那个 `device_id`（`1905892595378490`）另外还被单独拉黑（大量第三方共用），
     * 所以**不要**回落到它：没注册过时本字段就是空表，调用方要先注册。
     */
    @Volatile var deviceIdentity: Map<String,String> = emptyMap()
        set(value) { field=value.filterValues { it.isNotBlank() }; signer.updateDevice(deviceProfile()) }
    /**
     * 共用另一份仓库实例的**设备身份与注册 Cookie**（[deviceIdentity] 与 `registrationCookies`）。
     *
     * 播放预取会另开一个 [ContentRepository]（独立连接池与超时），它自己没注册过设备：
     * 不把这两个值带过去，它签名时只能用内置那份档案（`device_id` 被平台拉黑），
     * 取播放地址必然空响应——那一集永远预取不命中。
     */
    fun inheritDevice(from: ContentRepository) {
        deviceIdentity=from.deviceIdentity
        registrationCookies=from.registrationCookies
    }
    /**
     * 注册用的设备档案（机型 / 系统 / `cdid` / `openudid` …），与 [deviceIdentity] 分开存：
     * 注册要在**还没有** `device_id` 的时候发出去，注册完拿到的那对身份再交给 [deviceIdentity]。
     * 由界面层从本机设置注入（见 `Library.deviceProfile`）。
     */
    @Volatile var registerProfile: Map<String,String> = emptyMap()
    /**
     * 匿名注册一台设备，拿到平台认可的 `device_id` / `install_id`。
     *
     * 成功后**立刻**写入 [deviceIdentity]（后续请求马上用得上），并把下发的 Cookie 并进
     * 签名档案。失败（网络 / 服务端拒绝）返回 null，调用方按「没注册」处理即可——
     * 播放与搜索都会各自走回退路径，不会因为注册失败而整页报错。
     */
    fun registerDevice(): DeviceRegister.Registered? {
        val device=LinkedHashMap(registerProfile).apply { putAll(deviceIdentity) }
        val body=DeviceRegister.payload(device)
        val headers=DeviceRegister.headers(body,device)
        val registered=runCatching {
            val request=Request.Builder().url(DeviceRegister.url())
            headers.forEach { (key,value) -> request.header(key,value) }
            request.post(body.toRequestBody("application/octet-stream".toMediaType()))
            RequestScope.execute(http.newCall(request.build())) { response ->
                if(!response.isSuccessful) throw IOException("设备注册失败（HTTP ${response.code}）")
                val bytes=response.body?.bytes() ?: throw IOException("设备注册响应为空")
                DeviceRegister.parse(String(bytes,Charsets.UTF_8),response.headers("Set-Cookie"))
                    ?: throw IOException("设备注册被服务端拒绝")
            }
        }.getOrNull()
        if(registered!=null) {
            deviceIdentity=linkedMapOf("device_id" to registered.deviceId,"iid" to registered.installId)
            registrationCookies=LinkedHashMap(registrationCookies).apply { putAll(registered.cookies) }
        }
        return registered
    }
    /** 注册下发的 Cookie（`install_id` / `ttreq` / `store-region`），随注册请求一起带上。 */
    @Volatile private var registrationCookies: Map<String,String> = emptyMap()
        set(value) { field=LinkedHashMap(value) }
    /**
     * 签名与设备参数共用的**三层合并档案**（后一层压前一层）：
     *
     * 1. [VendorConstants.device]：内置档案，兜底用；
     * 2. [deviceIdentity]：本机身份（`device_id` / `iid`），来自 [registerDevice] 的注册结果；
     * 3. [credentials] 带来的**真机档案**：`device_type` / `cdid` / `klink_egdi` / `rom_version` …，
     *    写接口（追剧 / 进度）要与凭据一致，因此它优先级最高。
     */
    private fun deviceProfile(): Map<String,String> =
        LinkedHashMap(VendorConstants.device).apply {
            putAll(deviceIdentity)
            credentials?.device?.forEach { (key,value) -> if(value.isNotBlank()) this[key]=value }
        }
    /**
     * 弹幕接口是播放器接口，走 App 域名（与搜索 / 详情同一套设备参数）。
     * 站点有多条线路，单条失败时依次换线，最后一条仍失败才放弃本窗口。
     */
    /**
     * 「相关作品」接口的线路，按实测命中率排序（同一条线路约一半概率回空响应体，
     * 换线重发即可；六条线路轮一圈的成功率接近 1）。
     */
    private val relatedHosts=listOf(
        "api5-normal-hl.fqnovel.com","api5-normal-lf.fqnovel.com","api5-normal-sinfonlinec.fqnovel.com",
        "api5-normal-sinfonlinea.fqnovel.com","api5-normal-lq.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com"
    )
    /** 「相关作品」接口最多试几次（每次换一条线路）：空响应体是常态，不是错误。 */
    private val RELATED_ATTEMPTS=8
    /** 「相关作品」那一栏的 cell 标识：`show_type=547` / `cell_name="相关作品"`。 */
    private val RELATED_CELL_SHOW_TYPE=547
    private val RELATED_CELL_NAME="相关作品"
    /**
     * 相关作品的内存缓存条数：按剧集 ID 索引。
     *
     * 界面自己已经做了「同一部剧不重复预取」（`relatedFor`），这里再兜一层是为了
     * 「看一集 → 回简介 → 再点相关剧集」这条路径：那一栏的内容不会变，
     * 没必要每进一次详情就再打一次接口。
     */
    private val RELATED_CACHE_LIMIT=16
    private val relatedCache=linkedMapOf<String,RelatedWorks>()
    private val commentHosts=listOf("api5-normal-sinfonlinea.fqnovel.com","api5-normal-sinfonlinec.fqnovel.com","api5-normal-sinfonlineb.fqnovel.com","api5-normal-lf.fqnovel.com","api5-normal-lq.fqnovel.com","api5-normal-hl.fqnovel.com")
    /**
     * 播放接口空响应体的原地重发次数。
     *
     * 平台 2026-10-15 起对匿名设备一律回 `200 + 0 字节`；带上登录态后基本稳定，
     * 但仍偶发，因此同一条路径上先重发几次再换下一条——否则会把唯一能用的那条也跳过去。
     */
    private val PLAYBACK_RETRIES=3
    private fun text(url: String, signed: Signer.Request? = null): String {
        val request = Request.Builder().url(url).header("User-Agent",VendorConstants.VIDEO_UA).header("Accept-Language","zh-CN,zh;q=0.9")
        signed?.headers?.forEach { (k,v) -> request.header(k,v) }
        signed?.body?.let { request.post(it.toRequestBody("application/json; charset=utf-8".toMediaType())) }
        return RequestScope.execute(http.newCall(request.build())) { response ->
            if (!response.isSuccessful) throw IOException("内容请求失败（HTTP ${response.code}）")
            val body = response.body ?: throw IOException("内容响应为空")
            if (body.contentLength() > 16*1024*1024) throw IOException("内容响应过大")
            val input=body.byteStream(); val out=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
            while(out.size()<=16*1024*1024) { val n=input.read(buffer); if(n<0) break; out.write(buffer,0,n) }; val bytes=out.toByteArray()
            if(bytes.size > 16*1024*1024) throw IOException("内容响应过大")
            String(bytes,Charsets.UTF_8)
        }
    }
    /**
     * 协议常量：官方 App 里也是硬编码，不随设备变。
     *
     * 动态化**只针对设备属性**（机型 / 系统 / device_id / cdid …），
     * `aid` / `app_name` 这类跟着官方包走就行。
     */
    private val PROTOCOL_VALUES = linkedMapOf(
        "ac" to "wifi","aid" to "8662","app_name" to "novelread","ssmix" to "a",
        "device_platform" to "android","os" to "android","language" to "zh",
        "compliance_status" to "0","need_personal_recommend" to "1","player_so_load" to "1",
        "host_abi" to "arm64-v8a"
    )
    /**
     * 写接口（追剧 / 进度 / 历史）用的设备参数。
     *
     * 与 [appValues] 的两点不同，都是照官方抓包对齐的：
     * * 客户端版本用 `73932` / `7.3.9.32`（写接口走的是这一版，混用会被按旧客户端处理）；
     * * 叠加导入凭据时带来的**真机档案**（`cdid` / `klink_egdi` / `rom_version` 这些
     *   内置档案里根本没有，而官方请求每条都带）。
     */
    private fun writeValues(): LinkedHashMap<String,String> = deviceValues("73932","7.3.9.32")
    /** 搜索 / 弹幕这类 App 接口共用的设备参数：与播放器版本对齐，避免平台按旧版本拒绝。 */
    private fun appValues(): LinkedHashMap<String,String> = deviceValues("72232","7.2.2.32")
    /**
     * 设备参数的分层组装（后一层压前一层，合并规则见 [deviceProfile]）：
     *
     * 1. [VendorConstants.device]：内置档案，兜底用；
     * 2. [deviceIdentity]：本机身份（`device_id` / `iid`），来自 [registerDevice] 的注册结果；
     * 3. 导入凭据带来的**真机档案**：`device_type` / `device_brand` / `os_version` /
     *    `cdid` / `klink_egdi` / `rom_version` … 这些内置档案里没有或对不上的字段；
     * 3. [PROTOCOL_VALUES] 与客户端版本：协议常量，官方 App 也硬编码，
     *    放在最后一层保证 `aid` / `app_name` 不会被档案里的同名值冲掉。
     */
    private fun deviceValues(versionCode: String, versionName: String): LinkedHashMap<String,String> {
        val values=LinkedHashMap(deviceProfile())
        values.putAll(PROTOCOL_VALUES)
        for(key in listOf("version_code","manifest_version_code","update_version_code","pv_player")) values[key]=versionCode
        values["version_name"]=versionName
        return values
    }
    /**
     * 给 App 接口补上身份。两件事，分开做：
     *
     * 1. **设备 Cookie**：`registerDevice` 下发的 `install_id` / `ttreq` / `store-region`
     *    每个请求都要带——平台靠它们把请求认到那台已注册设备上（少了这一步，
     *    即使 `device_id` 已在 query 里，服务端也可能不认）。
     * 2. **登录态**（可选）：账号凭据的整段 Cookie，或历史遗留的 `sessionToken`。
     *    两者都没有就只带设备 Cookie——搜索与播放对匿名设备一样放行。
     */
    private fun Signer.Request.withSession(): Signer.Request {
        // 账号凭据优先：它带的是整套 Cookie（App 接口既要 Cookie 又要与之一致的设备参数）。
        val cookie=credentials?.cookie?.takeIf { it.isNotBlank() }
        if(cookie!=null) return copy(headers=headers+linkedMapOf("x-tt-token" to cookie,"Cookie" to cookie))
        val deviceCookie=registrationCookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }
        val token=sessionToken?.trim().orEmpty()
        if(token.isEmpty()) {
            return if(deviceCookie.isEmpty()) this else copy(headers=headers+linkedMapOf("Cookie" to deviceCookie))
        }
        val extra=linkedMapOf("x-tt-token" to token)
        // 只有整段 Cookie（含 `=`）才同时按 `Cookie` 头发一次：纯 token 不是 Cookie 串，
        // 混进 `Cookie` 会让服务端按 Cookie 解析。设备 Cookie 是另一回事，照常附上。
        when {
            token.contains('=') -> extra["Cookie"]=if(deviceCookie.isEmpty()) token else "$token; $deviceCookie"
            deviceCookie.isNotEmpty() -> extra["Cookie"]=deviceCookie
        }
        return copy(headers=headers+extra)
    }
    /** 有没有可用的登录态：搜索要不要优先走 App 接口、个人信息页要不要联网，都看它。 */
    val signedIn: Boolean get()=credentials?.configured==true || !sessionToken.isNullOrBlank()
    private fun router(url: String) = extractRouter(text(url)).getJSONObject("loaderData")
    private fun card(item: JSONObject): Series {
        val o=item.optJSONObject("video_data") ?: item
        val count=o.optInt("episode_cnt",o.optJSONObject("series_episode_info")?.optInt("episode_cnt",0) ?: 0)
        val tags=o.optJSONArray("tags") ?: o.optJSONArray("category_list") ?: JSONArray()
        return Series(o.str("series_id"),o.str("series_name").ifEmpty { o.str("series_title").ifEmpty { item.str("name") } },cleanUrl(o.str("series_cover")),o.str("series_intro"),o.str("episode_right_text").ifEmpty { if(count>0) "全 ${count} 集" else "" },(0 until minOf(tags.length(),5)).joinToString(" · ") { tags.optJSONObject(it)?.str("name") ?: tags.optString(it) })
    }
    private fun valid(rows: List<Series>) = rows.filter { it.id.matches(Regex("[0-9]{1,30}")) && it.title.isNotBlank() }.distinctBy { it.id }
    /**
     * 榜单页：四个热播榜（[RankBoard]）共用一份解析（见 [RankPage]），只有路由与 `rankKey` 不同。
     *
     * 首页的四个「热播…」剧架与排行榜页的四个榜单读的是**同一份数据**，
     * 因此剧架行末的「查看全部」直接进对应的榜单页，不再另做一套「热门集合页」。
     */
    fun rank(page: Int = 1, board: RankBoard = RankBoard.COMIC): CatalogPage {
        require(page in 1..100)
        return RankPage.parse(text("$site/rank/${board.route}?page=$page"),page,board)
    }
    /**
     * 首页四个「热播…」剧架的第 1 页：四个榜单彼此独立，串行要等四份 RTT，因此并发取。
     *
     * 单个榜单失败只让那一栏不出现（首页其余内容照常上屏），四个都失败才抛错。
     * 取消（切页 / 换标签）沿着 [RequestScope] 传进工作线程，在途的四个请求会一起收手。
     */
    fun homeFeed(): Map<RankBoard,CatalogPage> {
        val scope=RequestScope.current()
        val pool=java.util.concurrent.Executors.newFixedThreadPool(RankBoard.entries.size) { task ->
            Thread(task,"hgtv-homefeed").apply { isDaemon=true }
        }
        return try {
            val futures=RankBoard.entries.map { board ->
                board to pool.submit(java.util.concurrent.Callable { RequestScope.withScope(scope) { runCatching { rank(1,board) }.getOrNull() } })
            }
            val result=linkedMapOf<RankBoard,CatalogPage>()
            futures.forEach { (board,future) -> future.get()?.let { page -> result[board]=page } }
            if(result.values.none { it.items.isNotEmpty() }) throw IOException("热门榜暂不可用")
            result
        } finally {
            pool.shutdownNow()
        }
    }
    /**
     * 分类目录的一页：`/category/{path}?page=N`（`path` 来自 [CategoryCatalog]，例如 `comic-drama/fantasy`）。
     *
     * 结构与片库目录同构（`loaderData.category_$` 里的 `recommendList` + `pagination`），
     * 24 部一页；分类页**没有名次与热度**，所以 [CatalogPage.ranking] 恒为 null。
     */
    fun category(path: String, page: Int = 1): CatalogPage {
        require(path.isNotBlank() && path.length<=80 && page in 1..MAX_CATALOG_PAGE)
        val data=router("$site/category/$path"+if(page>1) "?page=$page" else "")
        val section=data.optJSONObject("category_$") ?: data.optJSONObject("category_page") ?: throw IOException("分类目录暂不可用")
        val rows=section.optJSONArray("recommendList") ?: section.optJSONObject("categoryData")?.optJSONArray("recommendList") ?: throw IOException("分类目录结构已变化")
        val items=valid(rows.objects().map(::card))
        val lastPage=section.optJSONObject("pagination")?.optInt("totalPages",1) ?: 1
        return CatalogPage(items,items.isNotEmpty() && page<minOf(lastPage,MAX_CATALOG_PAGE))
    }
    /**
     * 搜索：先走 App 搜索接口，拿不到数据时退回官网搜索。
     *
     * App 搜索接口 `POST /reading/bookapi/search/tab/v` 是唯一按内容类型分 tab、也唯一能翻页的入口
     * （第 2 页起带 `search_id` / `passback` / `next_offset`），所以它优先；配置 [sessionToken] 时
     * 它会按登录态返回数据，匿名设备则一律返回 HTTP 200 + 空响应体。
     *
     * 接口不可用时退回：第 1 页读官网搜索（`/search/{kw}`：一次只给约 10 条、忽略所有分页参数），
     * 第 2 页起从片库目录里筛片名含关键词的剧。搜索结果过期要如实抛出，
     * 让界面回到第 1 页重新搜，而不是拿官网的前 10 条顶替。
     */
    /**
     * 界面用的搜索：**不区分内容类型**（顶部那两个「漫剧 / 短剧」开关已经去掉）。
     *
     * App 搜索接口按 tab 分（`19` 漫剧、`11` 短剧 / 真人剧），这里两个 tab 各取一次再合并、
     * 按剧集 ID 去重；接口对匿名设备不可用时退回官网搜索（本身就不分类型），
     * 第 2 页起从**两本片库目录**（`comic-drama` 与 `real-drama`）里扫。
     */
    /**
     * 搜索提示词（联想词）：官网 PC 端搜索框用的就是这个接口
     * （前端 chunk 里的 `/incent_resource/suggestion`，见 docs/HONGGUO_API.md 3.5.1）。
     *
     * ```
     * GET https://hongguoduanju.com/incent_resource/suggestion?app_id=8662&web_id={随机 UUID}&query={关键词}&count=10
     * ```
     *
     * **不需要签名、不需要登录态**，只要求 `app_id=8662`（缺了它服务端返回 500）；`web_id` 可省。
     * `count` 有效上限 20（超过按 20 算），不传按 10；空关键词返回 500，因此调用前先判空。
     *
     * 响应里每条的 `video_data` 与卡片是同一套字段（`series_id` / `series_title` /
     * `series_cover` / `series_intro` / `episode_cnt` / `category_list`），因此直接复用 [card] 解析。
     * `word_type` 实测见过 `short_play_name`（剧名，带完整 `video_data`）、`title`、`common_query`、
     * `album`、`short_play_category`、`short_play_kol_user`——后几种的 `video_data` 是空对象，
     * 只能当查询词用。
     */
    fun suggestions(keyword: String, count: Int = 10): List<SearchSuggestion> {
        val clean=keyword.trim()
        if(clean.isEmpty()) return emptyList()
        val wanted=count.coerceIn(1,20)
        val url="$site/incent_resource/suggestion?app_id=$SUGGEST_APP_ID&web_id=$webId" +
            "&query=${Signer.encode(clean)}&count=$wanted"
        val rows=JSONObject(text(url)).optJSONArray("suggest_list")?.objects() ?: return emptyList()
        return rows.mapNotNull { row ->
            val name=row.str("name").ifEmpty { row.optJSONArray("display_words")?.optString(0).orEmpty() }.trim()
            if(name.isEmpty()) return@mapNotNull null
            // video_data 为空对象（title / common_query 这类）时不算剧集，只能当查询词。
            val video=row.optJSONObject("video_data")?.takeIf { it.length()>0 }
            val series=video?.let { card(it) }?.takeIf { it.id.matches(Regex("[0-9]{1,30}")) }
            SearchSuggestion(name,series,row.str("word_type"))
        }.distinctBy { it.name }.take(wanted)
    }
    /**
     * 热搜词：搜索页空输入时铺在下面那一排。
     *
     * 平台**没有**热搜榜接口（`/incent_resource/hot_word`、`/incent_resource/hot_search` 等都是 404），
     * 但 [suggestions] 这个联想词接口本身就是「按热度排的候选」：拿几个题材词当种子各问一次，
     * 把返回里**不是某一部剧**的那些（`title` / `common_query` / `short_play_category` / `category` /
     * `album`——`video_data` 是空对象）收集起来去重，就是一份现成的热词表。
     *
     * 只取种子词里靠前的若干条：联想词是按相关度排的，越靠前越像「大家在搜的」。
     * 全部种子词都失败时退回 [FALLBACK_HOT]：搜索页不能因为一个提示接口而空着。
     */
    fun hotSearches(limit: Int = 12): List<String> {
        val wanted=limit.coerceIn(1,30)
        // 种子词**并发**问（串行五个要 1.8s，并发后是单次请求的时间）。
        val pools=SUGGEST_SEEDS.map { seed -> java.util.concurrent.CompletableFuture.supplyAsync({ suggestionsPool(seed) }, SUGGEST_POOL) }
            .map { future -> runCatching { future.join() }.getOrDefault(emptyList()) }
        // 分层 + 层内轮询：
        // - 先按词类型分层（题材 / 分类 > 平台搜索建议 > 书名）；
        // - 同一层里**按种子轮询**交替取——一个种子单独就能填满整张表，
        //   顺着一个种子取的话整排热搜词都是同一个题材的变体（实测全是「…短剧」）。
        val seen=linkedSetOf<String>()
        HOT_WORD_ORDER.forEach { type ->
            if(seen.size>=wanted) return@forEach
            val lists=pools.map { pool -> pool.filter { it.second==type }.map { it.first } }
            var round=0
            while(seen.size<wanted && lists.any { it.size>round }) {
                lists.forEach { list -> list.getOrNull(round)?.let { if(seen.size<wanted) seen += it } }
                round++
            }
        }
        return if(seen.isEmpty()) FALLBACK_HOT.take(wanted) else seen.toList()
    }
    /** 一个种子词的候选：`(词, word_type)`，过滤掉剧名、过长过短的与重复的。 */
    private fun suggestionsPool(seed: String): List<Pair<String,String>> =
        runCatching { suggestions(seed,20) }.getOrDefault(emptyList())
            .filter { it.series==null && it.name.length in 2..12 }
            .map { it.name to it.wordType }
            .distinctBy { it.first }
    fun search(keyword: String, page: Int = 1): CatalogPage = search(keyword,page,ContentType.entries.toList())
    /** 只搜一种内容类型（App 搜索接口的一个 tab）：合并搜索与联调都走这一份实现。 */
    fun search(keyword: String, page: Int, type: ContentType): CatalogPage = search(keyword,page,listOf(type))
    private fun search(keyword: String, page: Int, types: List<ContentType>): CatalogPage {
        require(keyword.isNotBlank() && keyword.length<=80 && page in 1..100 && types.isNotEmpty())
        // 第 1 页总会试一次（平台恢复时能自动接回来）；后面几页在确认接口已关掉之后就不再空跑。
        if(page==1 || appSearchAlive!=false) {
            val items=mutableListOf<Series>()
            var hasMore=false; var answered=false; var expired=false
            // AI 剧在 App 搜索接口里没有对应的 tab（见 [ContentType.searchable]），不请求它。
            types.filter { it in ContentType.searchable }.distinct().forEach { type ->
                // 已经判死（空响应 = 设备没注册）就别再问另一个 tab：两个 tab 结论一致，
                // 多问一次只是把首屏又推后一个 RTT。
                if(appSearchAlive==false) return@forEach
                try {
                    val result=appSearch(keyword,page,type)
                    answered=true; items+=result.items; if(result.hasMore) hasMore=true
                } catch(e: SearchSessionExpiredException) { expired=true } catch(_: Exception) { }
            }
            // 有一个 tab 给了答案就用它：另一个 tab 空响应 / 少 tab 都不算失败。
            if(answered) return CatalogPage(valid(items),hasMore && page<100)
            // 所有 tab 都答不上、而且是游标过期：如实抛出去，让界面回到第 1 页重搜。
            if(expired && page>1) throw SearchSessionExpiredException()
        }
        return if(page==1) websiteSearch(keyword,types) else catalogSearch(keyword,page,types)
    }
    /**
     * 官网搜索：一次只给前 10 条，而且**没有任何分页参数**
     * （`page` / `page_num` / `offset` / `page_size` / `count` 全部被服务端忽略，实测同一批结果）。
     * 所以它只负责第 1 页：相关度最高的那几条，后面继续加载的内容由 [catalogSearch] 从片库目录里补。
     */
    private fun websiteSearch(keyword: String,types: List<ContentType>): CatalogPage {
        val data=router("$site/search/${Signer.encode(keyword)}?page=1")
        val section=data.optJSONObject("search_(keyword)/page") ?: data.optJSONObject("search_page") ?: throw IOException("搜索数据暂不可用")
        val rows=section.optJSONArray("searchList") ?: throw IOException("搜索数据结构已变化")
        val items=valid(rows.objects().map(::card))
        // 第 1 页开一次新的「已发过」名单：重新搜同一个词就是重新开始。
        synchronized(catalogSeen) {
            if(catalogSeen.size>=20) catalogSeen.keys.firstOrNull()?.let(catalogSeen::remove)
            catalogSeen[catalogKey(types,keyword)]=items.map { it.id }.toMutableSet()
        }
        // 空结果不是错误：搜索页自己会显示「没有找到相关…，换个关键词试试」。
        // hasMore 交给片库目录决定（下面每次都会真的去查，所以这里先给 true）。
        return CatalogPage(items,true)
    }
    /**
     * 搜索的第 2 页起：从片库目录（官网分类页）里筛出片名带关键词的剧。
     *
     * 官网搜索没有分页、App 搜索接口对匿名设备返回空响应，能往下翻的只有分类页（24 部一页、34 页）。
     * 每次往后扫 [SEARCH_SCAN_PAGES] 页，把命中的片名接在搜索结果后面；
     * 目录翻完就 hasMore=false，界面上的「已显示全部 N 部」就是真的到头了。
     */
    private fun catalogSearch(keyword: String,page: Int,types: List<ContentType>): CatalogPage {
        val start=1+(page-2)*SEARCH_SCAN_PAGES
        val items=mutableListOf<Series>()
        var morePagesLeft=false
        types.forEach { type ->
            var total=0
            for(offset in 0 until SEARCH_SCAN_PAGES) {
                val index=start+offset
                if(index>MAX_CATALOG_PAGE || (total>0 && index>total)) break
                val (matched,pages)=catalogPage(keyword,index,type)
                total=pages
                items+=matched
            }
            if(total>0 && start+SEARCH_SCAN_PAGES<=total) morePagesLeft=true
        }
        // 只回界面还没见过的条目；一个新条目都没有时只再给一次机会（第 2 页），
        // 之后就把 hasMore 收掉——否则关键词在片库里只有两三条时，用户会一直往下滚却什么都没有。
        val seen=synchronized(catalogSeen) { catalogSeen.getOrPut(catalogKey(types,keyword)) { mutableSetOf() } }
        val fresh=synchronized(catalogSeen) { items.distinctBy { it.id }.filter { seen.add(it.id) } }
        return CatalogPage(fresh,morePagesLeft && (fresh.isNotEmpty() || page<=2))
    }
    /** 片库目录第 [index] 页里片名含 [keyword] 的剧，外加这本目录一共多少页。 */
    private fun catalogPage(keyword: String,index: Int,type: ContentType): Pair<List<Series>,Int> {
        val data=router("$site/category/${type.catalog}?page=$index")
        val section=data.optJSONObject("category_$") ?: throw IOException("片库目录暂不可用")
        val rows=section.optJSONArray("recommendList") ?: throw IOException("片库目录结构已变化")
        val total=section.optJSONObject("pagination")?.optInt("totalPages",1) ?: 1
        val needle=keyword.filterNot(Char::isWhitespace)
        val matched=valid(rows.objects().map(::card)).filter { it.title.filterNot(Char::isWhitespace).contains(needle,ignoreCase=true) }
        return matched to minOf(total,MAX_CATALOG_PAGE)
    }
    private fun appSearch(keyword: String, page: Int, type: ContentType): CatalogPage {
        val values=appValues()
        values.putAll(linkedMapOf("query" to keyword,"count" to "20","offset" to ((page-1)*20).toString(),"tab_type" to type.searchTab.toString(),"bookshelf_search_plan" to "4","use_correct" to "true","user_is_login" to if(signedIn) "1" else "0"))
        val cursor=synchronized(searchCursors) {
            val now=clock()
            searchCursors.entries.removeAll { now-it.value.updatedAt>300000 || (page==1 && it.key.first==type && it.key.second==keyword) }
            searchCursors[Triple(type,keyword,page-1)]
        }
        // 漫剧翻页必须带上一页的游标。接口已经被平台关掉（空响应体）时不再抛「搜索结果已过期」：
        // 那样会把漫剧搜索钉死在第 1 页，走不到下面的官网搜索 + 片库目录补页。
        if(type==ContentType.COMIC && page>1 && cursor==null && appSearchAlive!=false) throw SearchSessionExpiredException()
        cursor?.let {
            if(it.searchId.isNotBlank()) values["search_id"]=it.searchId
            if(it.passback.isNotBlank()) values["passback"]=it.passback
            it.offset?.takeIf { offset -> offset>=0 }?.let { offset -> values["offset"]=offset.toString() }
        }
        val signed=signer.sign("https://api5-normal-sinfonlinea.fqnovel.com/reading/bookapi/search/tab/v",values)
        // **空响应体 = 这台设备没在服务端注册过，重发多少次都一样。**
        //
        // 平台按「服务端注册过的 `device_id` + `install_id`」放行：注册过的设备只回
        // `code 0` 或间歇性 `100103`，**从不回空响应**；没注册的一律空响应。
        // 而注册本身是匿名的（见 [registerDevice]），所以正确的做法是**去注册一台**，
        // 而不是原地重发——旧实现在这里退避 550/800/1050/1300/1550ms 重发 5 次，
        // 两个 tab 串行白等 12.8 秒，正是「搜索要十多秒才出结果」的来由。
        // 现在空响应**一次即判死**，立刻走官网搜索（实测约 250ms）。
        var raw=text(signed.url,signed.withSession())
        // 平台对匿名设备返回 HTTP 200 + 空响应体，这里必须先判空：
        // 直接丢给 JSONObject 会抛 JSONException（A JSONObject text must begin with '{'），
        // 那既不是可读的错误，也会把「App 搜索不可用、改用官网搜索」这条回退路径打断。
        if(raw.isBlank()) { appSearchAlive=false; throw IOException("App 搜索接口暂无响应") }
        // `100103` 才是真正的间歇性错误（同一设备约一半概率），短间隔重发几次即可。
        var attempt=0
        while(attempt<3 && raw.startsWith("""{"code":100103""")) {
            attempt++
            Thread.sleep(120L)
            raw=text(signed.url,signed.withSession())
            if(raw.isBlank()) { appSearchAlive=false; throw IOException("App 搜索接口暂无响应") }
        }
        appSearchAlive=true
        val data=JSONObject(raw)
        if(data.optInt("code",-1)!=0) throw IOException("搜索接口暂不可用")
        val tabs=data.optJSONArray("search_tabs") ?: data.optJSONObject("data")?.optJSONArray("search_tabs") ?: throw IOException("搜索格式异常")
        val tab=tabs.objects().firstOrNull { it.optInt("tab_type")==type.searchTab } ?: throw IOException("${type.label}搜索暂不可用")
        val list=mutableListOf<Series>()
        for(cell in (tab.optJSONArray("data") ?: JSONArray()).objects()) {
            val cells=mutableListOf(cell)
            runCatching { JSONObject(cell.str("lynx_data")).optJSONArray("cell_data")?.objects() }.getOrNull()?.let { cells.addAll(it) }
            for(row in cells) {
                val video=row.optJSONArray("video_data")?.objects()?.firstOrNull() ?: continue
                val id=video.str("series_id").ifEmpty { row.str("book_id").ifEmpty { row.str("search_result_id") } }
                val title=row.optJSONObject("search_high_light")?.optJSONObject("title")?.str("text").orEmpty().ifEmpty { video.str("title").ifEmpty { row.str("cell_name") } }
                val count=video.optInt("episode_cnt")
                list+=Series(id,title,cleanUrl(video.str("cover").ifEmpty { video.str("cover_url") }),video.str("video_desc"),if(count>0) "全 $count 集" else video.str("rec_text"),video.str("sub_title"))
            }
        }
        synchronized(searchCursors) {
            searchCursors[Triple(type,keyword,page)]=SearchCursor(tab.str("search_id"),tab.str("passback"),tab.str("next_offset").toIntOrNull(),clock())
            while(searchCursors.size>100) searchCursors.remove(searchCursors.keys.first())
        }
        return CatalogPage(valid(list),tab.optBoolean("has_more",false) && page<100)
    }
    // ------------------------------------------------------------------ 账号（个人信息页）
    /**
     * 账号接口共用的一次请求：带登录态、带设备参数、带签名。
     *
     * 这些接口都在 App 域名下（与搜索 / 弹幕同一套），因此设备参数用 [appValues]；
     * 写接口（POST + body）改用 [writeValues]——它们走的是官方 7.3.9.32 那一版，
     * 而且平台会拿签名里的设备档案做一致性判定。
     *
     * 空响应体在这个平台上很常见（网关挡掉），统一翻成可读的 [IOException]，
     * 免得把 JSONException 抛到界面上。
     */
    private fun accountJson(path: String, params: Map<String,String> = emptyMap(), body: ByteArray? = null): JSONObject {
        // 账号侧接口全部要求登录态：没有凭据时**不发请求**，直接给出可辨识的失败原因。
        if(!signedIn) throw SignInRequiredException()
        val values=if(body!=null) writeValues() else appValues()
        params.forEach { (key,value) -> values[key]=value }
        val signed=signer.sign("https://$ACCOUNT_HOST$path",values,body)
        val raw=text(signed.url,signed.withSession())
        if(raw.isBlank()) throw IOException("账号接口暂无响应（空响应体）")
        return JSONObject(raw)
    }

    /**
     * 空响应体重发：这个平台上「网关偶发挡掉」很常见（实测约一半概率），
     * 同一请求重发即可。只重试空 body，真的报错（100103 等）直接抛出去。
     */
    private fun retryBlank(attempts: Int = ACCOUNT_RETRIES, action: () -> JSONObject): JSONObject {
        var last: Exception?=null
        repeat(attempts) { attempt ->
            try { return action() } catch(e: Exception) {
                last=e
                if(!e.message.orEmpty().contains("空响应体")) throw e
                Thread.sleep(300L+attempt*250L)
            }
        }
        throw last ?: IOException("账号接口暂无响应")
    }

    /**
     * 账号资料。
     *
     * 两个接口一起用，因为信息是分开的（实测）：
     * * `user/info`（不带参数 = 当前登录用户）——昵称 / 头像 / **红果号**（字段名是 `req_id`）；
     * * `basic_info/get`——关注 / 粉丝 / 获赞三个计数。
     *
     * 注意 `profile/get` **必须带 `user_id`**（`?user_id=1739875314044148`），
     * 不带参数它回 `100107 network err`；它是看**别人**的资料用的，这里不用它。
     */
    fun accountProfile(): AccountProfile? {
        if(!signedIn) throw SignInRequiredException()
        val info=runCatching { accountJson("/reading/user/info/v1/") }.getOrNull()?.optJSONObject("data")
        val basic=runCatching { accountJson("/reading/user/basic_info/get/v1/") }.getOrNull()?.optJSONObject("data")
        return AccountParser.profile(info,basic)
    }

    /** 我的收藏：短剧书架（`bookshelf/video/info` 带封面与简介，比 `video/list` 那份完整）。 */
    fun accountFavorites(): List<AccountFavorite> {
        if(!signedIn) throw SignInRequiredException()
        return AccountParser.favorites(accountJson("/reading/bookapi/bookshelf/video/info/v1/"))
    }

    /**
     * 观看历史。
     *
     * 走 `read_progress/list`（不是 `read_history/list`——后者对短剧**恒返回空列表**，
     * 它是给小说用的）：`?book_types=2&limit=50`，短剧记录在 `video_data[]` 里。
     * 但它**只给 series_id**，所以再用 `multi-detail` 批量补片名与封面。
     *
     * 补元数据这一段失败就整条抛出（那一栏显示为空并在页面上写明原因），
     * 不返回只有 id 的条目——那在界面上是个空框，比少几条更难看。
     */
    fun accountHistory(): List<AccountHistory> {
        if(!signedIn) throw SignInRequiredException()
        val rows=AccountParser.history(accountJson("/reading/bookapi/read_progress/list/v1/",
            mapOf("book_types" to "2","limit" to MULTI_DETAIL_LIMIT.toString())))
        if(rows.isEmpty()) return rows
        // 这批接口只给 series_id，片名与封面必须再来这里补。**补不到就不显示这一条**：
        // 只留 id 的那一行在界面上是个空框（没有标题也没有封面），比少一条更糟。
        val details=AccountParser.multiDetail(accountJson("/reading/bookapi/multi-detail/v1/",
            mapOf("book_id" to rows.map { it.series.id }.joinToString(","),"book_type" to "2")))
        return rows.mapNotNull { row -> details[row.series.id]?.let { row.copy(series=it) } }
    }

    /** 只查一部剧的云端进度（不补片名封面）：打开剧集时用，别的设备刚看过的也能接上。未登录返回 null。 */
    fun accountProgress(seriesId: String): AccountHistory? {
        if(!signedIn) return null
        return AccountParser.history(accountJson("/reading/bookapi/read_progress/list/v1/",
            mapOf("book_types" to "2","limit" to MULTI_DETAIL_LIMIT.toString()))).firstOrNull { it.series.id==seriesId }
    }

    /**
     * 点赞：`ugc/action/mget` 的「我的点赞记录」。
     *
     * `action_type=3` 是「我点赞的」，`object_type_list=6,15,10` 是短剧相关的内容类型
     * （实测给 16 只回空；老的 `praise/user/record` 只覆盖送花那类，短剧点赞不在里面）。
     * 这个接口会间歇性回空响应体，重发即可。
     */
    fun accountLikes(): List<AccountLike> {
        if(!signedIn) throw SignInRequiredException()
        val params=mapOf("count" to "50","object_type_list" to "6,15,10","offset" to "0","action_type" to "3")
        return AccountParser.likes(retryBlank { accountJson("/reading/ugc/action/mget/v1/",params) })
    }

    /**
     * 预约 / 追更：`subscribe/list/v0` 的「预约」那一栏。
     *
     * `tab_type=13` 才是真数据（11/12 是空壳：`subscribe_items` 为 null）；
     * `is_online=true` 取「已上线」，false 取「待上线」。两批合起来就是 App 里那一栏的全部。
     */
    fun accountReserves(): List<AccountReserve> {
        if(!signedIn) throw SignInRequiredException()
        val online=AccountParser.reserves(accountJson("/reading/user/subscribe/list/v0/",
            mapOf("offset" to "0","limit" to "20","is_online" to "true","tab_type" to "13",
                "subscribe_order_type" to "0","swipe_type" to "0","need_calendar_schema" to "false","subscribe_offset" to "0")))
        val offline=runCatching { AccountParser.reserves(accountJson("/reading/user/subscribe/list/v0/",
            mapOf("offset" to "0","limit" to "20","is_online" to "false","tab_type" to "13",
                "subscribe_order_type" to "0","swipe_type" to "0","need_calendar_schema" to "false","subscribe_offset" to "0"))) }
            .getOrDefault(emptyList())
        return (online+offline).distinctBy { it.series.id }.sortedByDescending { it.updatedAt }
    }

    /**
     * 账号写接口：把本机的收藏 / 观看记录同步到云端。
     *
     * 三个接口都是 `POST` + JSON body，body 的字段名**必须与 App 的 protobuf/gson 模型一致**
     * （从 `com.dragon.read.rpc.model` 反编译出来的 `@SerializedName`）：
     *
     * * 收藏加：`bookshelf/add/v1/`，`{"book_id":["<series_id>"]}`——**数组**，给字符串会回 100103；
     * * 收藏删：`bookshelf/video/update/v1/`，`update_bookshelf_video_list[].video_shelf_operate_type=1`（DelShelf）；
     * * 进度 / 历史：`read_progress/upload/v1/`，`books[]` 里的字段是 **`book_id` / `item_id` /
     *   `read_timestamp_ms`**（不是 `series_id` / `vid` / `last_watch_time`——用错名字平台照回
     *   `code=0 SUCCESS`，但云端一个字节都不改）；`write_mode=1` = 同时写进度与历史。
     *
     * 写接口失败**不打扰用户**：本机记录已经落盘，云端同步只是尽力而为，返回 false 由调用方决定要不要提示。
     */
    fun accountAddFavorite(seriesId: String): Boolean = videoShelf(seriesId,VIDEO_SHELF_ADD_SHELF)

    /** 取消收藏：`bookshelf/video/update` 的 DelShelf（`bookshelf/delete` 实测不生效）。 */
    fun accountRemoveFavorite(seriesId: String): Boolean = videoShelf(seriesId,VIDEO_SHELF_DEL_SHELF)

    /**
     * 短剧收藏的增 / 删：`POST /reading/bookapi/bookshelf/video/update/v1/`。
     *
     * 短剧收藏（App 里叫「追剧」）只有这一个写接口，`bookshelf/add` 是**小说书架**，
     * 写进 `book_shelf_info_all`，而「我的收藏」短剧那一栏读的是 `video_shelf_info`——
     * 用错接口平台照样回 `code=0 SUCCESS`，短剧收藏却一点不变。
     *
     * `book_type` 用 [BOOK_TYPE_WATCH]（`ReadingBookType.Watch`）；
     * 平台偶发 `101144 HIT_SHARK_RULE`（风控限流），重发即可——官方包自己也带 pending 重试队列。
     */
    private fun videoShelf(seriesId: String,operate: Int): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        val entry=JSONObject()
            .put("book_id",seriesId).put("book_type",BOOK_TYPE_WATCH)
            .put("modify_time",clock())
            .put("video_shelf_operate_type",operate)
        // 「取消」在条目里带一个空的 `group_name`；「加入」不带。
        if(operate==VIDEO_SHELF_DEL_SHELF) entry.put("group_name","")
        // `shark_extra`（进入来源）是**body 的顶层字段**，不在条目里——这是 2026-10-15
        // 从官方包抓包核对出来的（`POST /reading/bookapi/bookshelf/video/update/v`）：
        // 官方那条请求的 body 形如
        // `{"is_cancelled":false,"shark_extra":{…},"update_bookshelf_video_list":[{…}]}`。
        // 早先把它塞进条目里，服务端照旧回 `101144 追剧失败`。
        val body=JSONObject()
            .put("is_cancelled",false)
        if(operate==VIDEO_SHELF_ADD_SHELF) {
            body.put("shark_extra",JSONObject()
                .put("enter_from","MainFragmentActivity")
                .put("previous_page","")
                .put("page_list","MainFragmentActivity")
                .put("is_active_behavior","true")
                .put("inactive_type","0"))
        }
        body.put("update_bookshelf_video_list",JSONArray().put(entry))
        val payload=body.toString().toByteArray()
        // 风控是**偶发**的（同一条请求重发两三次里总有一次过），因此这里自己重试：
        // [retryBlank] 只管空响应体，101144 要单独再发。
        repeat(ACCOUNT_WRITE_ATTEMPTS) { attempt ->
            var detail=""
            val ok=runCatching {
                val reply=accountJson("/reading/bookapi/bookshelf/video/update/v1/",emptyMap(),payload)
                val code=reply.optInt("code",-1)
                if(code!=0) detail="code=$code message=${reply.optString("message")}"
                code==0
            }.onFailure { detail="异常 ${it.message}" }.getOrDefault(false)
            if(ok) return true
            writeLog("收藏写接口失败 attempt=${attempt+1} operate=$operate id=$seriesId $detail")
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) Thread.sleep(ACCOUNT_WRITE_BACKOFF[attempt])
        }
        return false
    }

    /**
     * 上传观看进度（`write_mode=1` 会同时写进「历史」）。
     *
     * [episodeCount] 只用于云端「全 N 集」的展示，未知时传 0；
     * `progress_rate` 是 0~1 的小数串，平台用它画进度条。
     */
    fun accountUploadProgress(seriesId: String,episodeId: String,episodeIndex: Int,position: Long,duration: Long,episodeCount: Int=0): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        if(!episodeId.matches(Regex("[0-9]{1,30}"))) return false
        val rate=if(duration>0) (position.toDouble()/duration).coerceIn(0.0,1.0) else 0.0
        val entry=JSONObject()
            .put("book_id",seriesId)
            .put("item_id",episodeId)
            .put("vid_index",episodeIndex+1)
            .put("current_play_time",position.coerceAtLeast(0))
            .put("current_total_time",duration.coerceAtLeast(0))
            .put("episode_count",episodeCount.coerceAtLeast(0))
            .put("read_timestamp_ms",clock())
            .put("book_type",BOOK_TYPE_WATCH)
            .put("progress_rate","%.4f".format(java.util.Locale.US,rate))
            .put("player_cumulative_total_duration",position.coerceAtLeast(0))
        val body=JSONObject().put("books",JSONArray().put(entry)).put("write_mode",1).toString().toByteArray()
        repeat(ACCOUNT_WRITE_ATTEMPTS) { attempt ->
            var detail=""
            val ok=runCatching {
                val reply=accountJson("/reading/bookapi/read_progress/upload/v1/",emptyMap(),body)
                val code=reply.optInt("code",-1)
                if(code!=0) detail="code=$code message=${reply.optString("message")}"
                code==0
            }.onFailure { detail="异常 ${it.message}" }.getOrDefault(false)
            if(ok) return true
            writeLog("进度写接口失败 attempt=${attempt+1} id=$seriesId $detail")
            if(attempt<ACCOUNT_WRITE_ATTEMPTS-1) Thread.sleep(ACCOUNT_WRITE_BACKOFF[attempt])
        }
        return false
    }

    /**
     * 删除一条云端观看历史。
     *
     * 实测：`read_history/update` 必须带 `is_delete=true` + **`use_soft_delete=true`** +
     * `update_timestamp_ms`（带 `read_timestamp_ms` 会被放进 `update_fail_datas` 里拒绝）；
     * `book_id` 要给**数字**（字符串会被判 `100103`）。
     */
    fun accountDeleteHistory(seriesId: String): Boolean {
        if(!signedIn || !seriesId.matches(Regex("[0-9]{1,30}"))) return false
        val entry=JSONObject()
            .put("book_id",seriesId.toLong())
            .put("book_type",BOOK_TYPE_WATCH)
            .put("is_delete",true)
            .put("use_soft_delete",true)
            .put("update_timestamp_ms",clock())
        val body=JSONObject().put("update_datas",JSONArray().put(entry)).toString().toByteArray()
        return runCatching {
            val reply=accountJson("/reading/bookapi/read_history/update/v1/",emptyMap(),body)
            val code=reply.optInt("code",-1)
            val failed=(reply.optJSONObject("data")?.optJSONArray("update_fail_datas")?.length() ?: 0)
            if(code!=0 || failed>0) writeLog("历史删除写接口失败 id=$seriesId code=$code failed=$failed message=${reply.optString("message")}")
            code==0 && failed==0
        }.onFailure { writeLog("历史删除写接口异常 id=$seriesId ${it.message}") }.getOrDefault(false)
    }

    /**
     * 个人信息页要的全部内容：资料 + 四条列表，一次拉齐。
     *
     * 四条列表彼此独立：某一条失败只让它空着、把原因记进 [AccountData.errors]，
     * 其余三条照常显示。资料拉不到（或没配登录态）时 [AccountData.profile] 为 null，
     * 界面据此提示「需要先导入参数」。
     */
    fun accountData(): AccountData {
        // 未导入凭据时抛 [SignInRequiredException]（而不是回一份空数据）：
        // 空数据在界面上与「这个账号真的一条记录都没有」无法区分。
        if(!signedIn) throw SignInRequiredException()
        val errors=java.util.concurrent.ConcurrentHashMap<String,String>()
        fun <T> part(name: String, action: ()->T, fallback: T): T = try { action() } catch(e: Exception) {
            errors[name]=e.message.orEmpty().take(120); fallback
        }
        // 五个任务**并发**发出去：它们彼此独立，串行发要两三秒（每个请求都带签名，
        // 平台首包 200~600ms，个别还要重试）。并发之后整体耗时取决于最慢的那一个。
        val profile=accountPool.submit<AccountProfile?> { part("资料",{accountProfile()},null) }
        val favorites=accountPool.submit<List<AccountFavorite>> { part("收藏",{accountFavorites()},emptyList()) }
        val history=accountPool.submit<List<AccountHistory>> { part("历史",{accountHistory()},emptyList()) }
        val likes=accountPool.submit<List<AccountLike>> { part("点赞",{accountLikes()},emptyList()) }
        val reserves=accountPool.submit<List<AccountReserve>> { part("预约",{accountReserves()},emptyList()) }
        fun <T> take(future: java.util.concurrent.Future<T>, fallback: T): T =
            runCatching { future.get() }.getOrDefault(fallback)
        return AccountData(
            take(profile,null),take(favorites,emptyList()),take(history,emptyList()),
            take(likes,emptyList()),take(reserves,emptyList()),errors.toMap()
        )
    }

    fun detail(id: String): Detail {
        require(id.matches(Regex("[0-9]{1,30}")))
        val html=text("$site/detail?series_id=$id")
        val o=extractRouter(html).getJSONObject("loaderData").optJSONObject("detail_page")?.optJSONObject("seriesDetail") ?: throw IOException("剧集详情暂不可用")
        val raw=o.optJSONArray("vid_list") ?: throw IOException("分集暂不可用")
        val episodes=(0 until raw.length()).map { raw.getString(it) }
        if(episodes.isEmpty() || episodes.any { !it.matches(Regex("[0-9]{1,30}")) } || episodes.distinct().size!=episodes.size) throw IOException("分集数据异常")
        // 简介页下方那一栏不在这里取：它是 App 详情页的「相关作品」cell
        // （`GET /reading/bookapi/plan/v:version/`，见 [related]），要额外发一次请求，
        // 挂在详情上会让每次进简介页都多等一个 RTT。界面在详情上屏之后后台预取。
        return Detail(card(o).copy(id=id),episodes)
    }
    /**
     * 相关作品：App 详情页「相关作品」那一栏的内容，**一次请求拿全**（替代原先那套
     * 「官网搜索 + 沿季号逐季搜 + 扫 6 页片库目录」的召回：实测 2~4s、还要二十多次请求）。
     *
     * ```
     * GET https://{线路}/reading/bookapi/plan/v:version/
     *     ?scene=10&from=detail_page_more_related&book_id={剧集 ID}（见 [relatedRows]）
     * ```
     *
     * 这一栏**不在**官网详情页里，而是 App 详情页自己那个 cell：
     * `data[]` 里 `show_type=547` / `cell_name="相关作品"` 的 `video_data[]`，
     * 每条带 `season_index`（季号）、`tag_info.text`（`第N季` / `同IP`）与完整的卡片字段。
     * 实测一次给全：万妖图录传 26 条（13 季 + 13 部同 IP）、聚宝仙盆 45 条。
     *
     * [title] 是正在看的那一部的剧名（界面手里就有，不用再取一次详情）。
     * 用它按 [SeriesSeasons.normalize] 把同一部剧的各季挑出来——**不能只看 `tag_info`**：
     * 万妖图录传第一季那一页的 `第N季` 组里混着「月落镇魔第二季」这种同 IP 的另一个系列。
     *
     * 同系列各季（按季号升序）排在前面，其余相关作品接在后面、按剧集 ID 去重；
     * 正在看的那一部本身不出现（它自己就是当前页）。接口整轮失败时退回官网详情页的
     * 「相关短剧」（`recommendationGroups.related_list`，一次 HTML 请求，不做任何搜索）。
     */
    fun related(id: String, title: String = ""): RelatedWorks {
        require(id.matches(Regex("[0-9]{1,30}")))
        val current=title.trim()
        if(current.isNotEmpty()) synchronized(relatedCache) { relatedCache[id]?.let { return it } }
        val rows=runCatching { relatedRows(id) }.getOrNull().orEmpty()
        if(rows.isNotEmpty()) {
            val seasons=SeriesSeasons.siblings(current,rows.map { it.series })
            val seasonIds=seasons.map { it.id }.toSet()
            val others=rows.map { it.series }.filter { it.id!=id && it.id !in seasonIds }
            val works=RelatedWorks(seasons+others,seasons)
            if(current.isNotEmpty()) synchronized(relatedCache) {
                if(relatedCache.size>=RELATED_CACHE_LIMIT) relatedCache.keys.firstOrNull()?.let(relatedCache::remove)
                relatedCache[id]=works
            }
            return works
        }
        // 兜底：官网详情页第二段数据里的「相关短剧」。剧名在参数里没给时顺手从详情里读一次。
        val html=text("$site/detail?series_id=$id")
        val series=extractRouter(html).getJSONObject("loaderData").optJSONObject("detail_page")
            ?.optJSONObject("seriesDetail")?.let { card(it).copy(id=id) } ?: return RelatedWorks(emptyList(),emptyList())
        val fallback=relatedOf(html,id)
        val seasons=SeriesSeasons.siblings(current.ifEmpty { series.title },fallback)
        val seasonIds=seasons.map { it.id }.toSet()
        return RelatedWorks(seasons+fallback.filter { it.id !in seasonIds },seasons)
    }
    /** 从已经取到的详情页 HTML 里解析相关短剧；解析失败返回空列表。 */
    private fun relatedOf(html: String,id: String): List<Series> = runCatching {
        val body=extractRecommendations(html) ?: return emptyList()
        val groups=body.optJSONObject("recommendationGroups")
        val rows=groups?.optJSONArray("related_list") ?: body.optJSONArray("videoList") ?: return emptyList()
        valid(rows.objects().map(::card)).filterNot { it.id==id }
    }.getOrDefault(emptyList())

    /**
     * 「相关作品」里的一条：卡片 + 平台给的 `tag_info.text`（`第N季` / `同IP`）。
     *
     * [tag] 只当参考：界面上的季号仍由剧名推出来（见 [SeriesSeasons.badge]），
     * 因为同一个值在列表接口里没有、只有这一栏才有，两处得用同一套规则才不会互相打架。
     */
    private data class RelatedEntry(val series: Series, val tag: String)

    /**
     * 取「相关作品」那一栏的原始条目。
     *
     * 请求体（query）按官方 App 的形态给：`scene=10`、`from=detail_page_more_related`、
     * `book_id={剧集 ID}`，外加设备参数与签名（见 [appValues] / [Signer]）。
     * `from` 必须正是 `detail_page_more_related`：换别的取值服务端一律回空响应体。
     *
     * **空响应体是常态**（HTTP 200 + 0 字节，实测约一半概率），与设备是否注册无关：
     * 同一条线路连发几次里总有一次成功，所以这里换线重发、拿到 `code 0` 就返回；
     * 六条线路轮一圈都拿不到才抛出去，由 [related] 走官网兜底。
     */
    private fun relatedRows(id: String): List<RelatedEntry> {
        var failure: IOException?=null
        for(attempt in 0 until RELATED_ATTEMPTS) {
            val host=relatedHosts[attempt%relatedHosts.size]
            val values=appValues()
            values.putAll(linkedMapOf(
                "scene" to "10","from" to "detail_page_more_related","book_id" to id,
                "new_search_middle_page" to "false","bookstore_tab_type" to "0",
                "query_history_removed" to "false","user_is_login" to if(signedIn) "1" else "0",
                "bookstore_tab" to "0","search_middle_page_version" to "0","pad_column_detail" to "0",
                "hot_word_exchange" to "false","device_level" to "3","limit" to "0","offset" to "0",
                "is_horizontal_screen" to "false","reader_single_col_from_ip_strategy" to "false"
            ))
            try {
                val signed=signer.sign("https://$host/reading/bookapi/plan/v:version/",values)
                val raw=text(signed.url,signed.withSession())
                if(raw.isBlank()) { failure=IOException("相关作品接口暂无响应"); continue }
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) {
                    failure=IOException("相关作品接口暂不可用（${data.optInt("code")}）"); continue
                }
                val rows=parseRelatedRows(data)
                if(rows.isNotEmpty()) return rows
                failure=IOException("相关作品数据为空")
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "相关作品请求失败")
            }
        }
        throw failure ?: IOException("相关作品接口暂不可用")
    }
    /** 从「相关作品」响应里取出那一栏的卡片；结构与官网卡片同构，直接复用 [card] 的字段映射。 */
    private fun parseRelatedRows(data: JSONObject): List<RelatedEntry> {
        val cells=data.optJSONArray("data") ?: return emptyList()
        val rows=linkedMapOf<String,RelatedEntry>()
        for(cell in cells.objects()) {
            if(cell.optInt("show_type")!=RELATED_CELL_SHOW_TYPE && cell.str("cell_name")!=RELATED_CELL_NAME) continue
            for(v in (cell.optJSONArray("video_data") ?: JSONArray()).objects()) {
                val seriesId=v.str("series_id")
                val title=v.str("title")
                if(!seriesId.matches(Regex("[0-9]{1,30}")) || title.isBlank()) continue
                val count=v.optInt("episode_cnt")
                rows.putIfAbsent(seriesId,RelatedEntry(
                    Series(seriesId,title,cleanUrl(v.str("cover")),v.str("video_desc"),
                        if(count>0) "全 $count 集" else "",""),
                    v.optJSONObject("tag_info")?.str("text").orEmpty()
                ))
            }
        }
        return rows.values.toList()
    }
    /**
     * 取一集的播放模型（`video_model`）。
     *
     * 平台 2026-10-14 又改了一次播放接口：原先带场景段的
     * `/novel/player/multi_video_model/:dr_scene/v1/` 现在一律返回 **HTTP 200 + 空响应体**，
     * 旧路径 `/novel/player/multi_video_model/v1/` 返回 `Code 110001 未知异常`，
     * 表现出来还是「所有影片都放不了」。现在能用的那条是 `/novel/player/video_model/v1/`，
     * 而且**请求体形态也变了**：要 `video_id` 单值，不再是 `mixed_video_id_map`
     *（给 `mixed_video_id_map` / `vid` / `video_ids` 都返回 `100001 video_id invalid`）。
     *
     * 因此按「新路由 → 带场景段 → 旧路径」的顺序试，第一个能取到 `video_model` 的用；
     * 全失败时抛出最后一个错误，界面照常显示「播放接口暂不可用」。
     *
     * **空响应体必须当失败**：网关在业务处理前挡掉请求时就是 `200 + 0 字节`，
     * 这时业务码取不到、默认成 0，不判空就会被当成成功往下走（0.15.0 那次改版栽的就是这个）。
     *
     * 2026-10-15 平台又加了一道门槛：**匿名请求一律 `200 + 0 字节`**，路径与请求体都没变，
     * 因此五条候选路径会全部走完、最后抛出最后一条的错误码（`110001`），
     * 表现出来还是「所有影片都放不了」。带上账号 Cookie 后同一个请求 15/15 稳定命中 `code 0`，
     * 所以这里给播放请求补上 `Cookie` 头。
     *
     * 补的**只有** `Cookie`：这个接口对 `x-tt-token` 的长度敏感（实测 ≥99 字符直接判死），
     * 而账号凭据那份整段 cookie 串有 800 多字符，同时塞进 `x-tt-token` 会让请求回空
     *（搜索接口容忍这种写法，播放接口不容忍——真 App 用的是 355 字符的 hex token）。
     */
    private fun videoModel(id: String): JSONObject {
        val host="https://api5-normal-sinfonlineb.fqnovel.com"
        val biz="""{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024}"""
        // 新路由要 `video_id` 单值；带场景段与旧路径要 `mixed_video_id_map`（1004 = 短剧）。
        val videoMap="""{"biz_param":$biz,"mixed_video_id_map":{"1004":["$id"]}}"""
        val candidates=listOf(
            "/novel/player/video_model/v1/" to """{"video_id":"$id","biz_param":$biz}""",
            "/novel/player/multi_video_model/preload/v1/" to videoMap,
            "/novel/player/multi_video_model/default/v1/" to videoMap,
            "/novel/player/multi_video_model/detail/v1/" to videoMap,
            "/novel/player/multi_video_model/v1/" to videoMap
        )
        var failure: IOException?=null
        candidates.forEach { (path,body) ->
            val attempt=runCatching {
                val payload=body.toByteArray()
                // 播放接口对**匿名设备**也放行（官方 App 冷启动未登录即可播放，见 HAR），
                // 前提是这台设备已在服务端注册过（见 [registerDevice]）：未注册回 `101000 服务异常`
                // 或 `200 + 0 字节`，注册后回 `code 0` + 完整播放模型。
                // 因此这里用合并档案（含本机身份，见 [deviceProfile]），并带上注册下发的设备 Cookie；
                // 有账号凭据时以凭据的整段 Cookie 为准。
                val cookie=credentials?.cookie?.takeIf { it.isNotBlank() }
                    ?: registrationCookies.entries.joinToString("; ") { (name,value) -> "$name=$value" }.takeIf { it.isNotEmpty() }
                var response=""
                var blankRetry=0
                while(true) {
                    val signed=signer.sign(host+path,deviceProfile(),payload)
                    val request=if(cookie==null) signed else signed.copy(headers=signed.headers+linkedMapOf("Cookie" to cookie))
                    response=text(signed.url,request)
                    // 网关挡掉请求时是 200 + 0 字节：业务码取不到会默认成 0，必须先判空。
                    // 空响应先原地重发几次再换下一条：否则会把唯一能用的那条路径也跳过去。
                    if(response.isNotBlank() || blankRetry>=PLAYBACK_RETRIES) break
                    blankRetry++
                    Thread.sleep(200L+blankRetry*200L)
                }
                if(response.isBlank()) throw IOException("播放接口暂不可用（空响应）")
                val data=JSONObject(response)
                val code=data.optInt("Code",data.optInt("code",0))
                // 业务码非 0 就是这一条路不通（110001 是旧路径的「未知异常」，100001 是参数错误）。
                if(code!=0) throw IOException("播放接口暂不可用（${code}）")
                videoModelOf(data) ?: throw IOException("播放接口暂不可用（没有播放模型）")
            }
            attempt.getOrNull()?.let { return it }
            failure=attempt.exceptionOrNull() as? IOException ?: IOException(attempt.exceptionOrNull()?.message ?: "播放接口暂不可用")
        }
        throw failure ?: IOException("播放接口暂不可用")
    }
    /**
     * 从播放接口响应里取出 `video_model`：新路由在 `data.video_model`，
     * 带场景段 / 旧路径在 `data[<vid>].video_model`。
     * `video_model` 可能是对象，也可能是**字符串化的 JSON**，两种都要处理。
     */
    private fun videoModelOf(data: JSONObject): JSONObject? {
        val root=data.optJSONObject("data") ?: return null
        model(root.opt("video_model"))?.let { return it }
        val entry=root.keys().asSequence().mapNotNull { root.optJSONObject(it) }.firstOrNull() ?: return null
        return model(entry.opt("video_model"))
    }
    private fun model(value: Any?): JSONObject? = when(value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }
    fun stream(id: String, maxQuality: Int = 1080): StreamInfo {
        require(id.matches(Regex("[0-9]{1,30}")))
        val model=videoModel(id)
        val raw=model.opt("fallback_api")
        val fallback=when(raw) { is JSONObject -> raw.str("fallback_api"); is JSONArray -> raw.optString(0); else -> runCatching { JSONObject(raw.toString()).str("fallback_api") }.getOrDefault(raw.toString()) }
        if(!fallback.startsWith("https://")) throw IOException("播放地址格式异常")
        val info=JSONObject(text(fallback)).optJSONObject("video_info")?.optJSONObject("data") ?: throw IOException("没有播放资源")
        val videoList=info.optJSONObject("video_list") ?: throw IOException("没有可用清晰度")
        // ByteVC2 resources can expose playable audio but no video track in Android's MP4 extractor.
        // Keep missing legacy codec labels usable, but never select a known incompatible variant.
        val rows=videoList.keys().asSequence().mapNotNull { k -> videoList.optJSONObject(k)?.let { k to it } }.filter {
            it.second.str("main_url").isNotEmpty() && it.second.str("codec_type").lowercase() !in setOf("bytevc2","bvc2")
        }.map { (k,o) -> Triple(qualityNumber(o.str("quality_desc").ifEmpty { o.str("height").ifEmpty { o.str("vheight").ifEmpty { o.str("quality").ifEmpty { k } } } }),k,o) }.sortedByDescending { it.first }.toList()
        val selected=rows.firstOrNull { it.first<=maxQuality } ?: rows.lastOrNull() ?: throw IOException("没有电视可播放的清晰度，请稍后再试或选择其他剧集")
        val item=selected.third; var url=cleanUrl(item.str("main_url")); val seed=info.str("key_seed")
        if(seed.isNotEmpty()) url=MediaCrypto.decryptUrl(url,decode64(seed))
        if(!url.startsWith("https://") && !url.startsWith("http://")) throw IOException("播放地址无效")
        val key=item.str("spade_a").takeIf { it.isNotEmpty() }?.let(MediaCrypto::deriveKey)
        val quality=if(selected.first>0) "${selected.first}P"+(if(selected.first>maxQuality) "（兼容资源）" else "") else "自动"
        return StreamInfo(url,key,quality)
    }
    /**
     * 取一集的弹幕，也就是「原 App」播放时看到的同一份数据。
     *
     * 接口是播放器自己的评论接口：`POST /novel/commentapi/comment/list/{vid}/v1/`，
     * 请求体带 `comment_source=601`（短剧）、`server_channel=1000`、`group_type=30`、`comment_type=20`，
     * `business_param` 里再给剧集 ID 与本集时长；响应按 30 秒一个窗口返回，
     * `extra.next_query_danmaku_list_time` 是下一个窗口的起点，`common_list_info.has_more` 表示还没到片尾。
     *
     * [durationSeconds] 是这一集的时长（秒），用来判断何时停：为 0 时按窗口数量上限兜底。
     * [onWindow] 每拉到一个窗口就被回调一次（传当前已收集的全部弹幕，按时间排好），
     * 让播放界面边拉边画：整集拉完要十几秒，等全量再上屏等于前十几秒一条弹幕都没有。
     * 回调跑在调用线程上，调用方自己负责切回主线程。
     * 网络失败或平台不再返回弹幕时返回空列表，播放界面不显示任何弹幕，也不弹错误。
     */
    fun danmaku(vid: String, seriesId: String, durationSeconds: Long = 0, shouldStop: () -> Boolean = { false }, onWindow: (List<Danmaku>) -> Unit = {}): List<Danmaku> {
        require(vid.matches(Regex("[0-9]{1,30}")) && seriesId.matches(Regex("[0-9]{1,30}")))
        val durationMs=(durationSeconds.coerceIn(0,4*60*60))*1000
        val collected=linkedMapOf<String,Danmaku>()
        var startMs=0L
        var cursor=""
        // 知道时长就按时长算窗口；不知道时先按上限走——平台的 has_more 会在片尾自己翻成 false，
        // 所以「先多要几个窗口」不会把整部剧拉下来，只是让长剧也能取全。
        val planned=if(durationMs>0) (durationMs+COMMENT_WINDOW_MS-1)/COMMENT_WINDOW_MS+4L else MAX_DANMAKU_WINDOWS.toLong()
        val windows=planned.coerceIn(1L,MAX_DANMAKU_WINDOWS.toLong()).toInt()
        for(index in 0 until windows) {
            if(shouldStop()) break
            val page=runCatching { commentWindow(vid,seriesId,durationMs,startMs,cursor) }.getOrNull() ?: break
            var added=false
            page.items.forEach { if(collected.putIfAbsent("${it.offsetMs}:${it.text}",it)==null) added=true }
            if(added) onWindow(collected.values.sortedBy { it.offsetMs })
            if(collected.size>=MAX_DANMAKU) break
            if(page.nextStart<=startMs) break
            if(durationMs>0 && page.nextStart>=durationMs) break
            if(durationMs<=0 && !page.hasMore) break
            startMs=page.nextStart; cursor=page.cursor
            // 弹幕接口对匿名设备有频控：连发会返回风控错误，窗口之间留一点间隔。
            if(index<windows-1) runCatching { Thread.sleep(COMMENT_THROTTLE_MS) }
        }
        return collected.values.sortedBy { it.offsetMs }
    }
    /**
     * 调试用：一个窗口的原始 `data_list`。
     *
     * 只看接口到底发了什么字段（表情是不是只有文本一种形式），联调表情表时用，界面不调用。
     */
    fun danmakuRawWindow(vid: String,seriesId: String,startMs: Long=0): List<org.json.JSONObject> {
        val page=commentWindow(vid,seriesId,0,startMs,"")
        return page.raw
    }

    /** 一个 30 秒窗口的弹幕；所有线路都失败时抛 IOException，由 [danmaku] 决定是继续还是停下。 */
    private fun commentWindow(vid: String,seriesId: String,durationMs: Long,startMs: Long,cursor: String): DanmakuPage {
        val body=JSONObject()
            .put("comment_source",COMMENT_SOURCE).put("server_channel",SERVER_CHANNEL)
            .put("group_id",vid).put("group_type",30).put("comment_type",20).put("sort",1)
            .put("business_param",JSONObject().put("book_id",seriesId).put("start_offset_time",startMs)
                .put("playlet_item_duration",durationMs)
                .put("need_danmaku_guide_type",JSONArray(listOf(1,3,4,2))))
            .put("count",COMMENT_COUNT).put("cursor",cursor).put("aid",8662).put("compliance_status",0)
            .toString().toByteArray(Charsets.UTF_8)
        var failure: IOException?=null
        for(host in commentHosts) {
            try {
                val signed=signer.sign("https://$host/novel/commentapi/comment/list/$vid/v1/",appValues(),body)
                val headers=signed.headers.toMutableMap()
                // 这三个头是弹幕接口专用的，签名与普通接口共用同一套 App 参数。
                headers["comment-source"]=COMMENT_SOURCE.toString()
                headers["server-channel"]=SERVER_CHANNEL.toString()
                headers["x-ss-stub"]=""
                val raw=text(signed.url,Signer.Request(signed.url,headers,body))
                if(raw.isBlank()) throw IOException("弹幕接口暂无响应")
                val data=JSONObject(raw)
                if(data.optInt("code",-1)!=0) throw IOException("弹幕接口暂不可用（${data.optInt("code")}）")
                val payload=data.optJSONObject("data") ?: throw IOException("弹幕数据为空")
                val rows=payload.optJSONArray("data_list")
                val items=parseDanmakuRows(rows)
                val info=payload.optJSONObject("common_list_info")
                val next=payload.optJSONObject("extra")?.optLong("next_query_danmaku_list_time",-1L)?.takeIf { it>startMs }
                    ?: (startMs+COMMENT_WINDOW_MS)
                return DanmakuPage(items,next,info?.str("cursor").orEmpty(),info?.optBoolean("has_more",false) ?: false,rawDanmakuRows(rows))
            } catch(e: Exception) {
                failure=e as? IOException ?: IOException(e.message ?: "弹幕请求失败")
            }
        }
        throw failure ?: IOException("弹幕接口暂不可用")
    }
    companion object {
        /** 弹幕窗口固定 30 秒一个，与平台的 `next_query_danmaku_list_time` 一致。 */
        private const val COMMENT_WINDOW_MS=30_000L
        private const val COMMENT_SOURCE=601
        private const val SERVER_CHANNEL=1000
        private const val COMMENT_COUNT=90
        /** 一集最多拉多少个窗口（按 30 秒一窗，40 窗 = 20 分钟）。 */
        private const val MAX_DANMAKU_WINDOWS=40
        /** 一集最多保留多少条弹幕：低端电视的绘制预算，超出直接截断。 */
        private const val MAX_DANMAKU=3000
        /** 搜索第 2 页起每次往后扫几页片库目录（一页 24 部）。 */
        private const val SEARCH_SCAN_PAGES=3
        /** 片库目录最多翻到第几页。 */
        private const val MAX_CATALOG_PAGE=100
        /** 窗口之间的最小间隔，避开平台的弹幕频控。 */
        private const val COMMENT_THROTTLE_MS=180L
        /**
         * 相关短剧那段数据：`<script … data-fn-name="mergeLoaderData" data-fn-args="…">`。
         *
         * 参数是 HTML 实体转义过的 JSON 数组 `["detail_page",[{key:"recommendations",…}]]`，
         * 其中 `routerDataFnArgs[0]` 又是一层 JSON 字符串（内容是 `{videoList, recommendationGroups}`）。
         * 任何一步对不上都返回 null——相关短剧只是详情页的附加内容，不能影响详情本身。
         */
        fun extractRecommendations(html: String): JSONObject? = runCatching {
            val marker=html.indexOf("data-fn-name=\"mergeLoaderData\"")
            if(marker<0) return null
            val attr=html.indexOf("data-fn-args=\"",marker)
            if(attr<0) return null
            val start=attr+"data-fn-args=\"".length
            val end=html.indexOf('"',start)
            if(end<=start) return null
            // 结构：["detail_page", [ { key:"recommendations", routerDataFnArgs:["<JSON>"] } ]]
            val outer=JSONArray(unescapeHtml(html.substring(start,end)))
            val entries=outer.optJSONArray(1) ?: return null
            val args=entries.optJSONObject(0)?.optJSONArray("routerDataFnArgs") ?: return null
            val inner=args.optString(0,"")
            if(inner.isBlank()) return null
            JSONObject(inner)
        }.getOrNull()

        /** HTML 属性里的实体反转义：`&quot;` / `&#39;` / `&amp;` 三种在实测页面里出现过。 */
        private fun unescapeHtml(value: String)=value
            .replace("&quot;","\"")
            .replace("&#39;","'")
            .replace("&lt;","<")
            .replace("&gt;",">")
            .replace("&amp;","&")

        fun extractRouter(html: String): JSONObject {
            val marker=html.indexOf("_ROUTER_DATA"); if(marker<0) throw IOException("页面没有内容数据")
            val first=html.indexOf('{',marker); if(first<0) throw IOException("页面数据格式错误")
            var depth=0; var quoted=false; var escaped=false
            for(i in first until html.length) {
                val ch=html[i]
                if(quoted) { if(escaped) escaped=false else if(ch=='\\') escaped=true else if(ch=='"') quoted=false; continue }
                when(ch) { '"' -> quoted=true; '{','[' -> depth++; '}',']' -> { depth--; if(depth==0) return JSONObject(html.substring(first,i+1)) } }
            }
            throw IOException("页面数据不完整")
        }
        fun qualityNumber(value: String): Int = Regex("2160|1440|1080|720|576|540|480|360").find(value)?.value?.toInt() ?: mapOf("1920" to 1080,"1280" to 720,"1024" to 576,"854" to 480,"640" to 360)[value] ?: value.toIntOrNull() ?: 0
    }
}
