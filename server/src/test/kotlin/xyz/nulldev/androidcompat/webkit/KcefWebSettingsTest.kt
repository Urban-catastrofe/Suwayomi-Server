package xyz.nulldev.androidcompat.webkit

import xyz.nulldev.androidcompat.webkit.KcefWebSettings.UserAgentMetadata.Companion.BRAND_VERSION_LIST
import xyz.nulldev.androidcompat.webkit.KcefWebSettings.UserAgentMetadata.Companion.FORM_FACTORS
import xyz.nulldev.androidcompat.webkit.KcefWebSettings.UserAgentMetadata.Companion.FULL_VERSION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KcefWebSettingsTest {
    @Test
    fun `Android boundary arrays let extensions update browser client hints`() {
        val metadata = KcefWebSettings.defaultUserAgentMetadata().toMap().toMutableMap()
        val brands = metadata[BRAND_VERSION_LIST]
        assertTrue(brands is Array<*> && brands.isArrayOf<Array<String>>())
        assertTrue((metadata[FORM_FACTORS] as Array<*>).isArrayOf<String>())

        // Extensions use the Android boundary's String[][] contract to match the user agent.
        @Suppress("UNCHECKED_CAST")
        (brands as Array<Array<String>>).forEach { brand ->
            if (brand[0] == "Chromium") {
                brand[1] = "142"
                brand[2] = "142.0.0.0"
            }
        }
        metadata[FULL_VERSION] = "142.0.0.0"

        val updated = KcefWebSettings.UserAgentMetadata.fromMap(metadata)
        val fullVersionList = assertNotNull(updated.fullVersionList)
        assertEquals("142", updated.brands!!.first { it.brand == "Chromium" }.version)
        assertEquals("142.0.0.0", fullVersionList.first { it.brand == "Chromium" }.version)
        assertEquals(updated.fullVersion, fullVersionList.first { it.brand == "Chromium" }.version)
    }
}
