package app.retranslate.domain.books

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Scored charset detection for TXT files (PLAN.md §2.4, lesson 6 from the
 * old project): EUC-KR / GB18030 / Big5 files decoded with the wrong
 * charset become silent mojibake, so the decoder is *chosen by evidence*,
 * never assumed.
 *
 * Order: BOM → strict UTF-8 trial → statistical scoring of the three CJK
 * legacy charsets by common-character frequency. Scoring decodes the
 * sample with each candidate and weights what comes out: common
 * characters of that script score high, replacement characters and
 * private-use/control junk score heavily negative. The same bytes decode
 * to plausible-but-rare characters under a wrong charset, so the true
 * charset wins by a wide margin on real novel text.
 */
object CharsetDecoder {

    val UTF8: Charset = Charsets.UTF_8
    val EUC_KR: Charset = Charset.forName("EUC-KR")
    val GB18030: Charset = Charset.forName("GB18030")
    val BIG5: Charset = Charset.forName("Big5")

    private val LEGACY_CANDIDATES: List<Charset> = listOf(EUC_KR, GB18030, BIG5)

    /** How many bytes of the file head detection samples. */
    const val SAMPLE_BYTES = 64 * 1024

    fun detect(sample: ByteArray): Charset {
        if (sample.isEmpty()) return UTF8
        // BOMs are decisive.
        if (sample.size >= 3 &&
            sample[0] == 0xEF.toByte() && sample[1] == 0xBB.toByte() && sample[2] == 0xBF.toByte()
        ) return UTF8
        if (sample.size >= 2 && sample[0] == 0xFF.toByte() && sample[1] == 0xFE.toByte()) {
            return Charset.forName("UTF-16LE")
        }
        if (sample.size >= 2 && sample[0] == 0xFE.toByte() && sample[1] == 0xFF.toByte()) {
            return Charset.forName("UTF-16BE")
        }
        if (decodesStrictlyAsUtf8(sample)) return UTF8

        var best: Charset = EUC_KR
        var bestScore = Int.MIN_VALUE
        for (candidate in LEGACY_CANDIDATES) {
            val score = score(sample, candidate)
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun decodesStrictlyAsUtf8(sample: ByteArray): Boolean = try {
        UTF8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(sample))
        true
    } catch (e: CharacterCodingException) {
        false
    } catch (e: Exception) {
        false
    }

    private fun score(sample: ByteArray, charset: Charset): Int {
        val text = try {
            String(sample, charset)
        } catch (e: Exception) {
            return Int.MIN_VALUE / 2
        }
        var score = 0
        for (ch in text) {
            score += when {
                ch == '\uFFFD' -> -12
                ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> -6
                ch in '\uE000'..'\uF8FF' -> -6 // private use: wrong-charset smell
                charset == EUC_KR && ch in COMMON_HANGUL -> 6
                charset == EUC_KR && ch in '\uAC00'..'\uD7A3' -> 2
                charset == GB18030 && ch in COMMON_SIMPLIFIED -> 6
                charset == BIG5 && ch in COMMON_TRADITIONAL -> 6
                (charset == GB18030 || charset == BIG5) && ch in '\u4E00'..'\u9FFF' -> 1
                else -> 0
            }
        }
        return score
    }

    // Most frequent characters per script (frequency lists, trimmed to the
    // characters that do the discriminating; the sets differ exactly where
    // simplified and traditional usage differ).
    private val COMMON_HANGUL: Set<Char> =
        "은는이가을를의에와과도만으로자에서한할그녀다니고요제장것수있했주같나이렇때문현".toSet()
    private val COMMON_SIMPLIFIED: Set<Char> =
        ("的是了我不人在他有这上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两之万".toSet())
    private val COMMON_TRADITIONAL: Set<Char> =
        ("的是了我不人在他有這上們來到時大地為子中你說生國年著就那和要她出也得裡後自以會家可下而過天去能對小多然於心學麼之都好看起發當沒成只如事把還用第樣道想作種開美總從無情己面最女但現前些所同日手又行意動方期它頭經長兒回位分愛老因很給名法間斯知世什兩次使身者被高已親其進此話常與活正感麼這於學".toSet())
}
