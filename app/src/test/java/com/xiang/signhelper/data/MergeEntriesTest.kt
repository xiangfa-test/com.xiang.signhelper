package com.xiang.signhelper.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MergeEntriesTest {

    private fun entry(pkg: String, forged: String = "") = SignEntry(
        packageName = pkg,
        apkName = pkg,
        originalSignature = "aa",
        forgedSignature = forged,
        isForged = forged.isNotEmpty(),
    )

    @Test
    fun `new packages are appended`() {
        val result = mergeEntries(
            existing = listOf(entry("a"), entry("b")),
            incoming = listOf(entry("c")),
        )
        assertEquals(listOf("a", "b", "c"), result.entries.map { it.packageName })
        assertEquals(1, result.added)
        assertEquals(0, result.replaced)
    }

    @Test
    fun `duplicate package replaces in place`() {
        val result = mergeEntries(
            existing = listOf(entry("a"), entry("b")),
            incoming = listOf(entry("b", forged = "cc")),
        )
        assertEquals(listOf("a", "b"), result.entries.map { it.packageName })
        assertEquals("cc", result.entries[1].forgedSignature)
        assertEquals(0, result.added)
        assertEquals(1, result.replaced)
    }

    @Test
    fun `last duplicate in incoming wins`() {
        val result = mergeEntries(
            existing = emptyList(),
            incoming = listOf(entry("a", forged = "11"), entry("a", forged = "22")),
        )
        assertEquals(1, result.entries.size)
        assertEquals("22", result.entries[0].forgedSignature)
        assertEquals(1, result.added)
        assertEquals(1, result.replaced)
    }

    @Test
    fun `empty incoming keeps existing list`() {
        val existing = listOf(entry("a"))
        val result = mergeEntries(existing, emptyList())
        assertEquals(existing, result.entries)
        assertEquals(0, result.added)
        assertEquals(0, result.replaced)
    }
}
