package app.retranslate.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/** The instance editor's parse rule (§4.2): tidy, deduped, order kept. */
class InstancesViewModelTest {

    @Test
    fun `parse trims, drops blanks and duplicates`() {
        val parsed = InstancesViewModel.parse(
            "  https://a.example \n\nhttps://b.example\nhttps://a.example\n"
        )
        assertEquals(listOf("https://a.example", "https://b.example"), parsed)
    }

    @Test
    fun `parse of empty text is an empty list`() {
        assertEquals(emptyList<String>(), InstancesViewModel.parse("  \n \n"))
    }
}
