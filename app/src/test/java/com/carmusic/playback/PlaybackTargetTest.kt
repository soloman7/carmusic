package com.carmusic.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v5-D2 判定不变量:电台 "radio:" 前缀与音乐 trackId("platform:id")命名空间互斥。
 * 这是 PlayerManager 五处副作用 fence 的唯一判定来源,任何误判都会让电台媒体项
 * 涌入音乐副作用(历史污染/预签链崩溃/重签垃圾输出)。
 */
class PlaybackTargetTest {

    private val musicPlatforms = listOf("netease", "kuwo", "migu", "kugou", "qq", "jamendo", "maoer")

    @Test
    fun `radio media id is detected`() {
        assertTrue(PlaybackTarget.isRadioMediaId("radio:78012206-1aa1-11e9-a80b-52543be04c81"))
    }

    @Test
    fun `music media ids are never radio`() {
        musicPlatforms.forEach { p ->
            assertFalse(PlaybackTarget.isRadioMediaId("$p:123"))
        }
    }

    @Test
    fun `null is not radio`() {
        assertFalse(PlaybackTarget.isRadioMediaId(null))
    }

    @Test
    fun `prefix requires the colon boundary`() {
        assertFalse("无冒号不算电台", PlaybackTarget.isRadioMediaId("radioabc"))
        assertFalse("前缀必须逐字符匹配到冒号", PlaybackTarget.isRadioMediaId("radiolab:1"))
        assertTrue(PlaybackTarget.isRadioMediaId("radio:"))
    }

    @Test
    fun `no music platform collides with the radio namespace`() {
        // trackId = "$platform:$id";若未来新增平台名恰为 "radio",fence 将误杀真实歌曲——静态锁定
        musicPlatforms.forEach { assertNotEquals("radio", it) }
    }
}
