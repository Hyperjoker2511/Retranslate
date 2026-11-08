package app.retranslate.domain.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Script-based detection + code normalisation (PLAN.md §7.1). */
class LanguageDetectTest {

    @Test
    fun `Korean text detects ko`() {
        val text = "그는 천천히 눈을 떴다. 마을은 조용했고 바람만이 그의 옷자락을 흔들었다. 이제 시작이다, 그가 중얼거렸다."
        assertEquals("ko", LanguageDetector.detect(text))
    }

    @Test
    fun `simplified Chinese detects zh-CN`() {
        val text = "他缓缓睁开眼睛，村庄很安静，只有风吹动他的衣角。现在开始了，他低声说道，修炼之路漫长而艰险。"
        assertEquals("zh-CN", LanguageDetector.detect(text))
    }

    @Test
    fun `traditional Chinese detects zh-TW`() {
        val text = "他緩緩睜開眼睛，村莊很安靜，只有風吹動他的衣角。現在開始了，他低聲說道，修煉之路漫長而艱險。"
        assertEquals("zh-TW", LanguageDetector.detect(text))
    }

    @Test
    fun `Japanese detects ja`() {
        val text = "彼はゆっくりと目を開けた。村は静かで、風だけが彼の服の裾を揺らしていた。「さあ、始まるぞ」と彼はつぶやいた。"
        assertEquals("ja", LanguageDetector.detect(text))
    }

    @Test
    fun `English detects en`() {
        val text = "He opened his eyes slowly and the village was quiet. " +
            "\"Now it begins,\" he said, and the road ahead of him was long and hard, " +
            "but he was not afraid of the dark or the cold wind that came with the night."
        assertEquals("en", LanguageDetector.detect(text))
    }

    @Test
    fun `too little text detects nothing`() {
        assertNull(LanguageDetector.detect("안녕"))
        assertNull(LanguageDetector.detect(""))
    }

    @Test
    fun `non-English latin script is not guessed`() {
        // French: decisive Latin, but no English markers — must ask the user.
        val text = "Il ouvrit lentement les yeux et le village était silencieux. " +
            "Le vent seul agitait le pan de son vêtement pendant que la nuit tombait sur les toits."
        assertNull(LanguageDetector.detect(text))
    }

    // -- LanguageCodes.normalise -------------------------------------------

    @Test
    fun `normalise handles wild codes`() {
        assertEquals("en", LanguageCodes.normalise("en-US"))
        assertEquals("en", LanguageCodes.normalise("EN"))
        assertEquals("zh-CN", LanguageCodes.normalise("zh"))
        assertEquals("zh-CN", LanguageCodes.normalise("zh-Hans"))
        assertEquals("zh-TW", LanguageCodes.normalise("zh-Hant"))
        assertEquals("zh-TW", LanguageCodes.normalise("zh_TW"))
        assertEquals("ko", LanguageCodes.normalise("ko-KR"))
        assertEquals("he", LanguageCodes.normalise("iw"))
        assertNull(LanguageCodes.normalise("not-a-language"))
        assertNull(LanguageCodes.normalise(""))
        assertNull(LanguageCodes.normalise(null))
        assertTrue(LanguageCodes.normalise("ja") == "ja")
    }
}
