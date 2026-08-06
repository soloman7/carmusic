package com.carmusic.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueNavigatorTest {

    // ---- 空队列 ----

    @Test
    fun `empty queue returns null for both directions`() {
        val nav = QueueNavigator()
        PlayMode.entries.forEach { mode ->
            assertNull(nav.nextIndex(0, 0, mode))
            assertNull(nav.prevIndex(0, 0, mode))
        }
    }

    // ---- 单元素队列（play(track) 场景）----

    @Test
    fun `single item queue replays in loop modes, null in sequence`() {
        val nav = QueueNavigator()
        assertNull(nav.nextIndex(0, 1, PlayMode.SEQUENCE))
        assertNull(nav.prevIndex(0, 1, PlayMode.SEQUENCE))
        assertEquals(0, nav.nextIndex(0, 1, PlayMode.REPEAT_ALL))
        assertEquals(0, nav.prevIndex(0, 1, PlayMode.REPEAT_ALL))
        assertEquals(0, nav.nextIndex(0, 1, PlayMode.REPEAT_ONE))
        assertEquals(0, nav.prevIndex(0, 1, PlayMode.REPEAT_ONE))
        assertEquals(0, nav.nextIndex(0, 1, PlayMode.SHUFFLE) { 0 })
    }

    // ---- SEQUENCE ----

    @Test
    fun `sequence stops at queue end and start`() {
        val nav = QueueNavigator()
        assertEquals(1, nav.nextIndex(0, 3, PlayMode.SEQUENCE))
        assertEquals(2, nav.nextIndex(1, 3, PlayMode.SEQUENCE))
        assertNull(nav.nextIndex(2, 3, PlayMode.SEQUENCE))   // 末曲停住
        assertEquals(1, nav.prevIndex(2, 3, PlayMode.SEQUENCE))
        assertNull(nav.prevIndex(0, 3, PlayMode.SEQUENCE))   // 首曲停住
    }

    // ---- REPEAT_ALL ----

    @Test
    fun `repeat all wraps around both ends`() {
        val nav = QueueNavigator()
        assertEquals(0, nav.nextIndex(2, 3, PlayMode.REPEAT_ALL))   // 末曲回卷到第一首
        assertEquals(2, nav.prevIndex(0, 3, PlayMode.REPEAT_ALL))   // 首曲回卷到末曲
        // 连续上翻：末曲 → 倒数第二 → 倒数第三（用户抱怨的"回不到上上首"场景）
        assertEquals(1, nav.prevIndex(2, 3, PlayMode.REPEAT_ALL))
        assertEquals(0, nav.prevIndex(1, 3, PlayMode.REPEAT_ALL))
    }

    // ---- REPEAT_ONE：手动切歌不受影响 ----

    @Test
    fun `repeat one manual skip still moves by one with wrap`() {
        val nav = QueueNavigator()
        assertEquals(0, nav.nextIndex(2, 3, PlayMode.REPEAT_ONE))
        assertEquals(2, nav.prevIndex(0, 3, PlayMode.REPEAT_ONE))
    }

    // ---- SHUFFLE ----

    @Test
    fun `shuffle next never returns current index`() {
        val nav = QueueNavigator()
        repeat(50) {
            val next = nav.nextIndex(1, 4, PlayMode.SHUFFLE) { (0..2).random() }
            assertTrue(next in 0..3)
            assertTrue(next != 1)
        }
    }

    @Test
    fun `shuffle prev walks back through history to before-before last`() {
        val nav = QueueNavigator()
        // 0 → next → ? → next → ?，两次 prev 应能回到 0（"上上首"）
        val a = nav.nextIndex(0, 5, PlayMode.SHUFFLE) { 0 }!!   // r=0 < cur → 0? 不对，r>=c 才 +1；r=0,c=0 → r>=c → 1
        assertEquals(1, a)
        val b = nav.nextIndex(a, 5, PlayMode.SHUFFLE) { 1 }!!   // r=1 >= c=1 → 2
        assertEquals(2, b)
        assertEquals(a, nav.prevIndex(b, 5, PlayMode.SHUFFLE))  // 回到上一首
        assertEquals(0, nav.prevIndex(a, 5, PlayMode.SHUFFLE))  // 回到上上首
    }

    @Test
    fun `shuffle prev with empty history wraps to last at index 0`() {
        val nav = QueueNavigator()
        assertEquals(4, nav.prevIndex(0, 5, PlayMode.SHUFFLE))
    }

    @Test
    fun `reset clears shuffle history`() {
        val nav = QueueNavigator()
        nav.nextIndex(0, 5, PlayMode.SHUFFLE) { 0 }
        nav.reset()
        // 历史清空后，prev 退化为普通回卷
        assertEquals(4, nav.prevIndex(0, 5, PlayMode.SHUFFLE))
    }

    // ---- 异常输入 ----

    @Test
    fun `negative current index is clamped to zero`() {
        val nav = QueueNavigator()
        // C.INDEX_UNSET(-1) 场景
        assertEquals(1, nav.nextIndex(-1, 3, PlayMode.SEQUENCE))
        assertEquals(2, nav.prevIndex(-1, 3, PlayMode.REPEAT_ALL))
    }

    @Test
    fun `current index beyond count is clamped to last`() {
        val nav = QueueNavigator()
        assertNull(nav.nextIndex(99, 3, PlayMode.SEQUENCE))   // 视为末曲
        assertEquals(0, nav.nextIndex(99, 3, PlayMode.REPEAT_ALL))
    }
}
