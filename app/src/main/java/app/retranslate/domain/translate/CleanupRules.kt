package app.retranslate.domain.translate

/**
 * The fixed, tested cleanup rule set (PLAN.md §5.1): strips web-novel
 * junk — site banners, previous/next-chapter lines, translator credit
 * ads — before chunking. Applied identically to study samples (P5) and
 * target chapters so the Knowledge Base and the text agree.
 *
 * Deliberately conservative: only whole paragraphs that match a known
 * junk shape are dropped, story paragraphs are never edited in place,
 * and system-window paragraphs (`[ … ]`) are untouchable.
 *
 * P7 finalised the set: the P4–P6 rules plus social/donation promo
 * lines (Discord/Telegram/Patreon/Ko-fi) and the Korean/Chinese
 * aggregator "new address" notices. Every addition is anchored —
 * paragraph-start imperatives, platform link fragments, or the exact
 * notice phrases — so a story sentence merely mentioning a platform
 * survives (test-proven).
 */
object CleanupRules {

    /** Exact navigation labels (compared trimmed + lowercased). */
    private val NAV_LABELS = setOf(
        "previous chapter", "next chapter", "prev chapter", "previous", "next",
        "table of contents", "contents", "index",
        "이전 화", "다음 화", "이전화", "다음화", "목차",
        "上一章", "下一章", "上一页", "下一页", "目录", "目錄"
    )

    /** Banner shapes seen on MTL aggregator sites (whole-paragraph match). */
    private val BANNER_PATTERNS = listOf(
        Regex("""(?i)read (the )?(latest|newest) chapters? (at|on|only)\b.*"""),
        Regex("""(?i).*for (the )?fastest (release|update).*"""),
        Regex("""(?i)this chapter is (brought|updated|provided) by\b.*"""),
        Regex("""(?i)visit [a-z0-9.-]+\.[a-z]{2,}.*(read|chapter).*"""),
        Regex("""(?i)if you find any errors.*(let us know|report).*"""),
        // P7: social/donation promos start the paragraph with the imperative.
        Regex("""(?i)^(join|follow|support)( us)?\b.{0,100}(discord|telegram|patreon|ko-fi)\b.*"""),
        Regex("""(?i)^(support|donate)\b.{0,60}(patreon|ko-fi|paypal)\b.*""")
    )

    /** Platform link fragments — in a short paragraph these are always promo. */
    private val PROMO_LINK_FRAGMENTS = listOf("discord.gg/", "t.me/", "patreon.com/")
    private const val PROMO_LINK_MAX_CHARS = 200

    /**
     * Aggregator "our address changed" notices (P7): Korean 최신 주소
     * (latest address) / 주소 변경 (address change); Chinese 永久地址
     * (permanent address) / 最新地址 (latest address) / 地址发布页
     * (address release page). Short paragraphs only.
     */
    private val ADDRESS_NOTICE_FRAGMENTS = listOf(
        "최신 주소", "주소 변경", "永久地址", "最新地址", "地址发布", "地址發布"
    )
    private const val ADDRESS_NOTICE_MAX_CHARS = 120

    /** A paragraph that is nothing but a URL (a watermark line). */
    private val URL_ONLY =
        Regex("""(?i)(https?://)?(www\.)?([a-z0-9-]+\.)+[a-z]{2,}(/\S*)?""")

    /** Translator/editor credit lines count as ads only when short. */
    private const val CREDIT_MAX_CHARS = 120
    private val CREDIT_PREFIX = Regex("""(?i)^(translator|editor|tl|tn|t/n|proofreader)\s*[:：].*""")

    fun clean(text: String): String {
        if (text.isBlank()) return ""
        return TextChunker.paragraphsOf(text)
            .filterNot { isJunk(it) }
            .joinToString("\n\n")
    }

    fun isJunk(paragraph: String): Boolean {
        val p = paragraph.trim()
        if (p.isEmpty()) return true
        if (p.startsWith("[")) return false // system window — never junk
        if (p.lowercase() in NAV_LABELS) return true
        if (p.length <= CREDIT_MAX_CHARS && URL_ONLY.matches(p)) return true
        if (p.length <= CREDIT_MAX_CHARS && CREDIT_PREFIX.matches(p)) return true
        if (p.length <= PROMO_LINK_MAX_CHARS &&
            PROMO_LINK_FRAGMENTS.any { p.lowercase().contains(it) }
        ) {
            return true
        }
        if (p.length <= ADDRESS_NOTICE_MAX_CHARS &&
            ADDRESS_NOTICE_FRAGMENTS.any { p.contains(it) }
        ) {
            return true
        }
        return BANNER_PATTERNS.any { it.matches(p) }
    }
}
