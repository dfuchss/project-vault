package org.fuchss.projectvault.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class RecentVaultsTest {

    private fun store(max: Int = 10): Pair<RecentVaults, File> {
        val dir = Files.createTempDirectory("pv-recents").toFile()
        val file = File(dir, "recent.txt")
        return RecentVaults(file, max) to file
    }

    private fun vault(name: String) = File(Files.createTempDirectory("pv-v").toFile(), name)

    @Test
    fun `add puts most recent first and de-duplicates`() {
        val (recents, _) = store()
        val a = vault("a.pvault")
        val b = vault("b.pvault")
        recents.add(a)
        recents.add(b)
        assertEquals(listOf(b.absolutePath, a.absolutePath), recents.list().map { it.absolutePath })

        // Re-adding an existing vault moves it to the top without duplicating.
        recents.add(a)
        assertEquals(listOf(a.absolutePath, b.absolutePath), recents.list().map { it.absolutePath })
    }

    @Test
    fun `respects the cap`() {
        val (recents, _) = store(max = 2)
        val a = vault("a.pvault"); val b = vault("b.pvault"); val c = vault("c.pvault")
        recents.add(a); recents.add(b); recents.add(c)
        assertEquals(listOf(c.absolutePath, b.absolutePath), recents.list().map { it.absolutePath })
    }

    @Test
    fun `remove and persistence across instances`() {
        val (recents, file) = store()
        val a = vault("a.pvault"); val b = vault("b.pvault")
        recents.add(a); recents.add(b)
        recents.remove(a)
        assertEquals(listOf(b.absolutePath), RecentVaults(file).list().map { it.absolutePath })
    }
}
