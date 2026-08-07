package org.fuchss.projectvault.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MismatchWarningsTest {

    @Test
    fun `no warnings when account fields unset or matching`() {
        assertTrue(ImportService.mismatchWarnings(null, null, "DKB", "DE111").isEmpty())
        // IBAN comparison ignores spacing/case.
        assertTrue(
            ImportService.mismatchWarnings("DKB", "DE02 1203 0000 0000 0000 00", "DKB", "DE02120300000000000000").isEmpty(),
        )
    }

    @Test
    fun `warns on IBAN mismatch`() {
        val warnings = ImportService.mismatchWarnings("DKB", "DE99 0000 0000", "DKB", "DE02120300000000000000")
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("IBAN"))
    }

    @Test
    fun `warns on bank mismatch`() {
        val warnings = ImportService.mismatchWarnings("ING", null, "DKB", null)
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("Bank"))
    }

    @Test
    fun `bank match is lenient (substring either direction)`() {
        assertTrue(ImportService.mismatchWarnings("DKB Girokonto", null, "DKB", null).isEmpty())
    }
}
