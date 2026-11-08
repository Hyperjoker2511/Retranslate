package app.retranslate.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** P8: the Licences screen must credit every direct bundled component. */
class LicencesCatalogTest {

    @Test
    fun `catalog is complete and every entry names a licence`() {
        val names = LicencesCatalog.libraries.map { it.name }
        assertEquals(names.size, names.distinct().size)
        assertTrue(LicencesCatalog.libraries.all { it.licence.isNotBlank() })
        assertTrue(LicencesCatalog.libraries.all { it.usedFor.isNotBlank() })
        assertTrue(names.any { it.contains("PDFBox") })
        assertTrue(names.any { it.contains("KXml2") })
        assertTrue(names.any { it.contains("OkHttp") })
        assertTrue(names.any { it.contains("WorkManager") })
    }
}
