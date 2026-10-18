package com.hgtv.star.core

/** 弹幕里的表情标签：`[名字]` / `【名字】` / `［名字］`，与 [DanmakuEmoji] 同一条规则。 */
private val tag=Regex("""\[([^\[\]【】［］\r\n]{1,16})\]|【([^\[\]【】［］\r\n]{1,16})】|［([^\[\]【】［］\r\n]{1,16})］""")

fun main(args: Array<String>) {
    val repository=ContentRepository()
    // 搜索接口要求登录态（匿名设备返回 200 + 空 body），联调时可用 --token <x-tt-token> 带上。
    val tokenAt=args.indexOf("--token")
    if(tokenAt>=0) repository.sessionToken=args.getOrNull(tokenAt+1)
    fun keywordArg(): String? = args.withIndex().firstOrNull { (index,value) -> !value.startsWith("--") && value.isNotBlank() && index!=tokenAt+1 }?.value
    // 短信验证码登录实验（见 SmsLoginLab）：--sms-send <手机号> [--type 24]，收到码后 --sms-verify <验证码>。
    if(args.contains("--sms-send")) return SmsLoginLab.send(args[args.indexOf("--sms-send")+1],args.getOrNull(args.indexOf("--type")+1)?.takeIf { "--type" in args } ?: "24")
    if(args.contains("--sms-verify")) return SmsLoginLab.verify(args[args.indexOf("--sms-verify")+1])
    if(args.contains("--ranking")) {
        // 四个热播榜逐个验证：分页、名次、热度、更新时间；再验证首页四栏（并发取）与它们一致。
        try {
            val boards=if(args.contains("--board")) listOf(RankBoard.fromStored(keywordArg())) else RankBoard.entries.toList()
            boards.forEach { board ->
                val first=repository.rank(1,board)
                val metadata=checkNotNull(first.ranking) { "${board.label} 没有名次数据" }
                check(first.items.isNotEmpty())
                val seen=mutableSetOf<String>()
                for(pageNumber in listOf(1,2,metadata.totalPages).distinct().filter { it<=metadata.totalPages }) {
                    val result=if(pageNumber==1) first else repository.rank(pageNumber,board)
                    val ranking=checkNotNull(result.ranking)
                    check(result.items.isNotEmpty() && result.items.none { it.id in seen })
                    seen.addAll(result.items.map { it.id })
                    val ranks=result.items.map { checkNotNull(ranking.positions[it.id]).rank }
                    check(ranks.all { it!=null && it>0 } && ranks.filterNotNull().zipWithNext().all { (a,b) -> a<b }) { "${board.label} page=$pageNumber 名次异常：$ranks" }
                    // 热度偶尔整页缺失（服务端 SSR 抽风），只提示不判失败：名次与分页才是硬要求。
                    val cold=result.items.filter { ranking.positions[it.id]!!.heat.isBlank() }
                    if(cold.isNotEmpty()) println("   注意：page=$pageNumber 有 ${cold.size} 条没有热度")
                    check(result.hasMore==(pageNumber<ranking.totalPages))
                    println("board=${board.label} page=$pageNumber/${ranking.totalPages}; items=${result.items.size}; ranks=${ranks.first()}..${ranks.last()}; firstHeat=${ranking.positions[result.items.first().id]!!.heat}; updated=${ranking.updatedText}")
                }
            }
            if(!args.contains("--board")) {
                val feed=repository.homeFeed()
                RankBoard.entries.forEach { board ->
                    val page=feed[board]
                    println("homeFeed ${board.label}: ${page?.items?.size ?: 0} 部; 首部=${page?.items?.firstOrNull()?.title.orEmpty()}")
                }
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--category")) {
        // 分类目录联调：逐个大类各取「全部」与第一个小类的第 1 / 2 页。
        try {
            CategoryCatalog.groups.forEach { group ->
                val picked=listOfNotNull(group.tags.firstOrNull(),group.tags.getOrNull(1))
                picked.forEach { tag ->
                    val first=repository.category(tag.path)
                    val second=if(first.hasMore) repository.category(tag.path,2) else null
                    val overlap=second?.let { next -> first.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size } ?: 0
                    println("${CategoryCatalog.describe(tag.path)}: page1=${first.items.size} page2=${second?.items?.size ?: 0} overlap=$overlap 首部=${first.items.firstOrNull()?.title.orEmpty()}")
                }
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--search")) {
        // 搜索联调：默认「聚宝」（漫剧），也可以给关键词与 --short。
        try {
            val type=if(args.contains("--short")) ContentType.SHORT else ContentType.COMIC
            val keyword=keywordArg() ?: "聚宝"
            val seen=mutableSetOf<String>()
            for(page in 1..6) {
                val result=try { repository.search(keyword,page,type) } catch(problem: Exception) {
                    println("search=$keyword type=${type.label} page=$page failed=${problem.message}")
                    if(repository.sessionToken.isNullOrBlank()) println("   提示：App 搜索接口匿名不可用时已退回官网搜索；两条路都失败请检查网络，或用 --token <x-tt-token> 让搜索直接使用 App 接口。")
                    break
                }
                val fresh=result.items.filter { seen.add(it.id) }
                println("search=$keyword type=${type.label} page=$page items=${result.items.size} new=${fresh.size} hasMore=${result.hasMore}")
                fresh.take(3).forEach { println("   ${it.id} ${it.title}") }
                if(!result.hasMore) break
            }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--suggest")) {
        // 搜索提示词联调：一个关键词一次，验证条数、字段与「哪些条目能直接进详情」。
        try {
            val keyword=keywordArg() ?: "仙"
            val rows=repository.suggestions(keyword,20)
            println("suggest=$keyword 条数=${rows.size}")
            rows.forEach { row -> println("   ${if(row.series!=null) "剧集 ${row.series.id}" else "查询词"} | ${row.name}") }
            check(rows.size<=20)
            // 空关键词不该发请求（服务端会 500），这里直接返回空表。
            check(repository.suggestions("   ").isEmpty())
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--hot")) {
        // 热搜词联调：种子词各问一次联想接口，收集「不是某一部剧」的那些词。
        try {
            val started=System.currentTimeMillis()
            val words=repository.hotSearches(12)
            println("hot 共 ${words.size} 条，${System.currentTimeMillis()-started}ms")
            words.forEach { println("   $it") }
            check(words.size<=12)
            check(words.distinct().size==words.size)
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--related")) {
        // 相关短剧联调：默认取短剧首页第一部，也可以直接给剧集 ID。
        try {
            val seriesId=args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: repository.rank(1,RankBoard.COMIC).items.first().id
            // 详情本身不带相关剧集：界面上是「详情先上屏 + 相关剧集后台预取」，
            // 这里分别量一下两段（详情那段就是用户实际等待的时间）。
            val started=System.currentTimeMillis()
            val detail=repository.detail(seriesId)
            val detailMs=System.currentTimeMillis()-started
            println("detail=${detail.series.title}; episodes=${detail.episodes.size}; ${detailMs}ms（不含相关剧集）")
            val relatedStart=System.currentTimeMillis()
            val works=repository.related(seriesId,detail.series.title)
            val relatedMs=System.currentTimeMillis()-relatedStart
            println("related 共 ${works.items.size} 条，${relatedMs}ms")
            works.items.take(14).forEach { println("   ${it.id} ${it.title} | ${it.badge} | 第 ${SeriesSeasons.season(it.title)} 季") }
            // 同一系列必须按季号升序、且不混入别的剧；相关短剧接在后面且不重复。
            val numbers=works.seasons.map { SeriesSeasons.season(it.title) }
            check(numbers==numbers.sorted()) { "同系列没有按季号升序：$numbers" }
            check(works.seasons.all { SeriesSeasons.normalize(it.title)==SeriesSeasons.normalize(detail.series.title) }) { "混入了别的剧" }
            check(works.items.map { it.id }.distinct().size==works.items.size) { "列表里有重复条目" }
            check(works.items.take(works.seasons.size).map { it.id }==works.seasons.map { it.id }) { "同系列没有排在前面" }
            // 第二次取（界面重复点开）应当命中缓存、几乎不花时间。
            val againStart=System.currentTimeMillis()
            val again=repository.related(seriesId,detail.series.title)
            println("再取一次 ${System.currentTimeMillis()-againStart}ms（再发一次接口请求）；条数一致=${again.items.size==works.items.size}")
            println("同系列 ${works.seasons.size} 季：${works.seasons.joinToString("、") { it.title }}")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--emoji-raw")) {
        // 看接口原始 JSON：带方括号标签的那几条，字段到底长什么样（表情有没有结构化字段）。
        try {
            val work=repository.rank(1,RankBoard.COMIC).items.first()
            val detail=repository.detail(work.id)
            val episode=detail.episodes.first()
            val rows=repository.danmakuRawWindow(episode,detail.series.id)
            var printed=0
            rows.forEach { row ->
                val text=row.optJSONObject("comment")?.optJSONObject("common")?.optJSONObject("content")?.optString("text").orEmpty()
                if(text.contains("[") || text.contains("【")) {
                    if(printed<6) { println(row.toString()); printed++ }
                }
            }
            println("窗口内共 ${rows.size} 条，带标签的打印了 $printed 条")
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--emoji")) {
        // 弹幕表情盘点：把一批剧的弹幕拉下来，统计正文里出现的方括号标签并逐条对照 [DanmakuEmoji]。
        // 用法：--emoji [部数] [每部集数]，默认 12 部、每部第 1 集；结果按出现次数排序打印。
        // 改表情表之前先跑这个：认不出来的名字就是要在表里补的名字。
        try {
            val numbers=args.filter { it.matches(Regex("[0-9]{1,3}")) }.map { it.toInt() }
            val works=numbers.getOrNull(0) ?: 12
            val perWork=numbers.getOrNull(1) ?: 1
            val seen=linkedMapOf<String,Int>()
            val samples=linkedMapOf<String,String>()
            var lines=0
            val catalog=repository.rank(1,RankBoard.COMIC).items.take(works)
            catalog.forEachIndexed { index,work ->
                runCatching {
                    val detail=repository.detail(work.id)
                    detail.episodes.take(perWork).forEach { episode ->
                        val items=repository.danmaku(episode,detail.series.id)
                        lines+=items.size
                        items.forEach { item ->
                            tag.findAll(item.text).forEach { match ->
                                val name=match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: return@forEach
                                seen[name]=(seen[name] ?: 0)+1
                                samples.putIfAbsent(name,item.text)
                            }
                        }
                    }
                    println("[${index+1}/${catalog.size}] 《${detail.series.title}》 ${detail.episodes.take(perWork).size} 集：累计 ${lines} 条弹幕、${seen.size} 个标签")
                }.onFailure { println("[${index+1}/${catalog.size}] ${work.title} 失败：${it.message}") }
            }
            println("=== 共 $lines 条弹幕、${seen.size} 个不同标签 ===")
            val unknown=seen.filterKeys { DanmakuEmoji.glyph(it)==null }
            println("--- 认不出的标签 ${unknown.size} 个（按出现次数）---")
            unknown.entries.sortedByDescending { it.value }.forEach { println("  ${it.key}\t${it.value}\t例：${samples[it.key]}") }
            println("--- 全部标签（按出现次数）---")
            seen.entries.sortedByDescending { it.value }.forEach { println("  ${it.key}\t${it.value}") }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    if(args.contains("--danmaku")) {
        // 弹幕接口联调：默认取漫剧热播榜第一部，也可以直接给剧集 ID。
        try {
            val seriesId=args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: repository.rank(1,RankBoard.COMIC).items.first().id
            val detail=repository.detail(seriesId)
            val episode=detail.episodes.first()
            val items=repository.danmaku(episode,detail.series.id)
            println("danmaku series=${detail.series.title}; episodes=${detail.episodes.size}; vid=$episode; count=${items.size}")
            println("span=${items.firstOrNull()?.offsetMs}ms..${items.lastOrNull()?.offsetMs}ms; top=${items.maxByOrNull { it.diggCount }?.diggCount}赞")
            items.take(3).forEach { println("  ${it.offsetMs}ms [${it.diggCount}] ${it.text}") }
        } finally {
            repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
        }
        return
    }
    val keyword=keywordArg() ?: "修仙"
    // 搜索优先走 App 搜索接口，匿名设备下退回官网搜索；两条路都失败也不打断后面的首页 / 详情 / 播放探测。
    // 顶部那两个「漫剧 / 短剧」开关已经去掉，这里走的就是界面用的那一条：不分类型。
    val search=try { repository.search(keyword) } catch(problem: Exception) {
        println("search failed=${problem.message}")
        if(repository.sessionToken.isNullOrBlank()) println("   提示：App 搜索接口匿名不可用时已退回官网搜索；两条路都失败请检查网络，或用 --token <x-tt-token> 让搜索直接使用 App 接口。")
        null
    }
    if(search!=null) {
        println("search=$keyword; items=${search.items.size}; hasMore=${search.hasMore}")
        if(search.hasMore) {
            val next=repository.search(keyword,2)
            println("searchPage2=${next.items.size}; overlap=${search.items.map { it.id }.intersect(next.items.map { it.id }.toSet()).size}")
        }
    }
    val feed=repository.homeFeed()
    feed.forEach { (board,page) -> println("homeFeed ${board.label}: items=${page.items.size}; hasMore=${page.hasMore}; 首部=${page.items.firstOrNull()?.title.orEmpty()}") }
    val detail=repository.detail(args.firstOrNull { it.matches(Regex("[0-9]{1,30}")) } ?: feed.values.firstOrNull()?.items?.firstOrNull()?.id ?: return)
    println("detail=${detail.series.title}; episodes=${detail.episodes.size}")
    val stream=repository.stream(detail.episodes.first())
    RemoteVideo(repository.http,stream).use { remote ->
        remote.prepare(); val first=remote.read(0,1024*1024)
        val tail=remote.read((remote.total-65536).coerceAtLeast(0),65536)
        println("quality=${stream.quality}; total=${remote.total}; first=${first.size}; tail=${tail.size}; header=${String(first,4,4)}")
        if(args.contains("--save")) java.io.File("/tmp/hongguo-kotlin-first.mp4").writeBytes(first)
    }
    repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown()
}
