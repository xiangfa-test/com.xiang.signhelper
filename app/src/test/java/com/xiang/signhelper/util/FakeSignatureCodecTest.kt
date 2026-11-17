package com.xiang.signhelper.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FakeSignatureCodec] 的纯字符串规则。
 *
 * 这些规则同时被 hook 端（[com.xiang.signhelper.hook.SignatureForge]）和 UI 端
 * （[com.xiang.signhelper.data.SignStore]）使用，写错了会表现为"配置看着没问题、
 * 就是不生效"，所以单独钉住。
 */
class FakeSignatureCodecTest {

    @Test
    fun `单行 hex 原样保留并小写`() {
        assertEquals(listOf("abcdef12"), FakeSignatureCodec.splitHexLines("ABCDEF12"))
    }

    @Test
    fun `多行被视为多条签名且保持顺序`() {
        val raw = "aa11\nbb22\r\ncc33"
        assertEquals(listOf("aa11", "bb22", "cc33"), FakeSignatureCodec.splitHexLines(raw))
    }

    @Test
    fun `逗号与分号也是分隔符`() {
        assertEquals(
            listOf("aa11", "bb22", "cc33"),
            FakeSignatureCodec.splitHexLines("aa11, bb22; cc33"),
        )
    }

    @Test
    fun `冒号与短横线被剔除`() {
        assertEquals(listOf("aabbcc"), FakeSignatureCodec.splitHexLines("AA:BB-CC"))
    }

    @Test
    fun `空行与纯分隔符行被丢弃`() {
        assertEquals(listOf("aa11"), FakeSignatureCodec.splitHexLines("\n\n  \naa11\n ,, \n"))
    }

    @Test
    fun `空输入返回空列表`() {
        assertTrue(FakeSignatureCodec.splitHexLines(null).isEmpty())
        assertTrue(FakeSignatureCodec.splitHexLines("").isEmpty())
        assertTrue(FakeSignatureCodec.splitHexLines("   \n\t ").isEmpty())
    }

    @Test
    fun `joinLines 是 splitHexLines 的逆操作`() {
        val raw = "AA:BB\n\ncc"
        val joined = FakeSignatureCodec.joinLines(FakeSignatureCodec.splitHexLines(raw))
        assertEquals("aabb\ncc", joined)
        assertEquals(FakeSignatureCodec.splitHexLines(raw), FakeSignatureCodec.splitHexLines(joined))
    }

    @Test
    fun `decodeHex 拒绝奇数长度与非十六进制`() {
        assertNull(FakeSignatureCodec.decodeHex("abc"))
        assertNull(FakeSignatureCodec.decodeHex("zz"))
        assertEquals(listOf<Byte>(0x0A, 0xFF.toByte()), FakeSignatureCodec.decodeHex("0aff")!!.toList())
    }
}
