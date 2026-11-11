package com.xiang.signhelper.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignEntryTest {

    @Test
    fun `effective requires enabled and non blank forged signature`() {
        val base = SignEntry(
            packageName = "com.example.app",
            apkName = "Example",
            originalSignature = "aabb",
            forgedSignature = "",
            isForged = false,
        )
        assertFalse(base.effective)
        assertFalse(base.copy(isForged = true).effective)
        assertFalse(base.copy(forgedSignature = "ccdd").effective)
        assertTrue(base.copy(forgedSignature = "ccdd", isForged = true).effective)
    }

    @Test
    fun `json round trip keeps all fields`() {
        val entry = SignEntry(
            packageName = "com.example.app",
            apkName = "Example",
            originalSignature = "aa",
            forgedSignature = "bb",
            isForged = true,
        )
        val restored = SignEntry.fromJson(entry.toJson())
        assertEquals(entry, restored)
    }

    @Test
    fun `json without package name is rejected`() {
        val json = org.json.JSONObject().apply {
            put("apkName", "No package")
            put("originalSignature", "aa")
        }
        assertNull(SignEntry.fromJson(json))
    }

    @Test
    fun `json with missing optional fields uses safe defaults`() {
        val json = org.json.JSONObject().apply {
            put("packageName", "com.example.app")
        }
        val entry = SignEntry.fromJson(json)
        requireNotNull(entry)
        assertEquals("com.example.app", entry.packageName)
        assertEquals("com.example.app", entry.apkName)
        assertEquals("", entry.originalSignature)
        assertEquals("", entry.forgedSignature)
        assertFalse(entry.isForged)
    }
}
