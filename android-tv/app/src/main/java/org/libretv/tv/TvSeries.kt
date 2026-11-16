package org.libretv.tv

/**
 * 同系列作品：从片名推出系列名（去掉季数、副标题、括号说明），再判断搜索结果是不是同一系列、排第几季。
 * 「爱情公寓3」→「爱情公寓」，「庆余年第二季」→「庆余年」，「007：大破量子危机」→「007」。
 * 同系列要求片名以系列名开头、后面紧跟季数或副标题标记，「繁花」不会把「繁花烬处」算进来。
 */
internal object TvSeries {
    private const val NUMERALS = "0-9一二三四五六七八九十"
    private val parenthesis = Regex("\\s*[（(][^）)]*[）)]\\s*$")
    private val subtitle = Regex("\\s*[：:·—]\\s*.*$|\\s+-\\s+.*$")
    private val chineseSeason = Regex("\\s*第[$NUMERALS]+[季部].*$")
    private val englishSeason = Regex("\\s*(?:season|s)\\s*\\d+.*$", RegexOption.IGNORE_CASE)
    private val trailingNumber = Regex("\\s*\\d{1,2}$")
    /** 片名后面紧跟这些，才算同一系列：分隔符、季数、数字续作、电影版/前传等 */
    private val follower = Regex("^(?:[：:·—\\-（(]|第[$NUMERALS]+[季部]|(?:season|s)\\s*\\d|\\d|之|大电影|电影版|剧场版|前传|续集|外传|番外)", RegexOption.IGNORE_CASE)

    fun base(name: String): String {
        var value = name.trim().replace(parenthesis, "")
        value = value.replace(subtitle, "").replace(chineseSeason, "").replace(englishSeason, "").trim()
        // 「复仇者联盟3」去掉末尾数字；「2046」「1917」「007」这种整个片名就是数字的保留
        val stripped = value.replace(trailingNumber, "").trim()
        if (stripped.length >= 2 && stripped.any { !it.isDigit() }) value = stripped
        return value.ifEmpty { name.trim() }
    }

    fun sameSeries(name: String, base: String): Boolean {
        val value = name.trim()
        if (base.length < 2 || !value.startsWith(base)) return false
        val rest = value.substring(base.length).trim()
        return rest.isEmpty() || follower.containsMatchIn(rest)
    }

    /** 排序用的季数：系列名本身是第 1 季，「第二季」「2」「Season 2」按数字，电影版、番外等排在各季之后 */
    fun season(name: String, base: String): Int {
        val rest = name.trim().removePrefix(base).trim()
        if (rest.isEmpty() || subtitle.matches(rest) && !rest.first().isDigit()) return if (rest.isEmpty()) 1 else 50
        Regex("^第([$NUMERALS]+)[季部]").find(rest)?.let { return number(it.groupValues[1]) ?: 50 }
        Regex("^(?:season|s)\\s*(\\d+)", RegexOption.IGNORE_CASE).find(rest)?.let { return it.groupValues[1].toInt() }
        Regex("^(\\d{1,2})(?!\\d)").find(rest)?.let { return it.groupValues[1].toInt() }
        return 50
    }

    /** 片源备注像电影（「正片」「HD」「TC」等且不带「集」）：排在各季之后，不和第 1 季合并 */
    fun movie(remarks: String) = !remarks.contains("集") && Regex("正片|HD|TC|BD|1080|4K|高清|超清|蓝光|中字|国语|粤语", RegexOption.IGNORE_CASE).containsMatchIn(remarks)

    /**
     * 整理同系列结果：去掉当前这部及与它同季的，同一季只留来源最多的一个（「爱情公寓1」与「爱情公寓第一季」），
     * 按季数排序，电影版与番外排在最后。entries 为 (片名, 备注, 来源数)，返回保留下来的序号。
     */
    fun arrange(entries: List<Triple<String, String, Int>>, base: String, current: String): List<Int> {
        val currentSeason = season(current, base).takeIf { it < 50 }
        val slots = LinkedHashMap<String, Int>()
        entries.forEachIndexed { i, (name, remarks, sources) ->
            if (!sameSeries(name, base) || name.trim() == current.trim()) return@forEachIndexed
            val film = movie(remarks)
            val number = if (film) 50 else season(name, base)
            if (!film && number == currentSeason) return@forEachIndexed
            val slot = if (film || number >= 50) "other:${name.trim()}" else "season:$number"
            val kept = slots[slot]
            if (kept == null || sources > entries[kept].third) slots[slot] = i
        }
        return slots.values.sortedWith(compareBy({ if (movie(entries[it].second)) 50 else season(entries[it].first, base) }, { entries[it].first }))
    }

    private fun number(text: String): Int? {
        text.toIntOrNull()?.let { return it }
        val digits = "一二三四五六七八九"
        return when {
            text == "十" -> 10
            text.length == 1 -> digits.indexOf(text[0]).takeIf { it >= 0 }?.plus(1)
            text.startsWith("十") -> digits.indexOf(text[1]).takeIf { it >= 0 }?.plus(11)
            text.endsWith("十") -> digits.indexOf(text[0]).takeIf { it >= 0 }?.let { (it + 1) * 10 }
            text.length == 3 && text[1] == '十' -> digits.indexOf(text[0]).takeIf { it >= 0 }?.let { a -> digits.indexOf(text[2]).takeIf { it >= 0 }?.let { b -> (a + 1) * 10 + b + 1 } }
            else -> null
        }
    }
}
