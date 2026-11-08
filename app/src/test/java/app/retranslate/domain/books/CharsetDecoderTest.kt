package app.retranslate.domain.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * Charset scoring (PLAN.md §2.4): real encoded samples in each
 * candidate charset must be detected as themselves — and must NOT be
 * detected as one of the others (that's the mojibake failure mode).
 */
class CharsetDecoderTest {

    private val koreanNovel =
        "제12장 검의 길. 그는 천천히 눈을 떴다. 마을은 조용했고, 바람만이 그의 옷자락을 흔들었다. " +
            "\"이제 시작이다.\" 그가 중얼거렸다. 수련의 길은 멀고도 험했지만, 그는 결코 포기하지 않았다. " +
            "어머니의 마지막 말을 떠올리며 그는 검을 고쳐 쥐었다. 오늘도 그는 앞으로 나아간다."

    private val simplifiedNovel =
        "第12章 剑之道。他缓缓睁开眼睛。村庄很安静，只有风吹动他的衣角。" +
            "\"现在开始了。\"他低声说道。修炼之路漫长而艰险，但他从未放弃。" +
            "想起母亲最后的话，他握紧了手中的剑。今天，他继续向前走。"

    private val traditionalNovel =
        "第12章 劍之道。他緩緩睜開眼睛。村莊很安靜，只有風吹動他的衣角。" +
            "「現在開始了。」他低聲說道。修煉之路漫長而艱險，但他從未放棄。" +
            "想起母親最後的話，他握緊了手中的劍。今天，他繼續向前走。"

    @Test
    fun `EUC-KR sample detects as EUC-KR`() {
        val bytes = (koreanNovel.repeat(4)).toByteArray(Charset.forName("EUC-KR"))
        assertEquals(CharsetDecoder.EUC_KR, CharsetDecoder.detect(bytes))
    }

    @Test
    fun `GB18030 sample detects as GB18030`() {
        val bytes = (simplifiedNovel.repeat(4)).toByteArray(Charset.forName("GB18030"))
        assertEquals(CharsetDecoder.GB18030, CharsetDecoder.detect(bytes))
    }

    @Test
    fun `Big5 sample detects as Big5`() {
        val bytes = (traditionalNovel.repeat(4)).toByteArray(Charset.forName("Big5"))
        assertEquals(CharsetDecoder.BIG5, CharsetDecoder.detect(bytes))
    }

    @Test
    fun `UTF-8 Korean detects as UTF-8`() {
        val bytes = (koreanNovel.repeat(2)).toByteArray(Charsets.UTF_8)
        assertEquals(Charsets.UTF_8, CharsetDecoder.detect(bytes))
    }

    @Test
    fun `UTF-8 with BOM detects as UTF-8`() {
        val body = "Hello world, this is a plain English chapter.".toByteArray(Charsets.UTF_8)
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body
        assertEquals(Charsets.UTF_8, CharsetDecoder.detect(bytes))
    }

    @Test
    fun `plain ASCII detects as UTF-8`() {
        val bytes = "Chapter 1\nIt was a bright morning.".toByteArray(Charsets.US_ASCII)
        assertEquals(Charsets.UTF_8, CharsetDecoder.detect(bytes))
    }

    @Test
    fun `detected charset round-trips the original text`() {
        val original = simplifiedNovel.repeat(6)
        val bytes = original.toByteArray(Charset.forName("GB18030"))
        val detected = CharsetDecoder.detect(bytes)
        assertTrue(String(bytes, detected).startsWith("第12章"))
    }

    @Test
    fun `empty sample falls back to UTF-8`() {
        assertEquals(Charsets.UTF_8, CharsetDecoder.detect(ByteArray(0)))
    }
}
