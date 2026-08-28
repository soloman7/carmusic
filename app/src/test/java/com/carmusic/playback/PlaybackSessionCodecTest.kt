package com.carmusic.playback

import com.carmusic.source.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionCodecTest {

    private fun sampleQueue() = listOf(
        Track(platform = "netease", id = "1", title = "晴天", artist = "周杰伦", album = "叶惠美", duration = 269),
        Track(
            platform = "migu", id = "2", title = "Foobar & (Live) 【HD】",
            artist = "某人/合唱团", coverUrl = "http://example.com/c.jpg",
            duration = 200, extra = mapOf("grey" to "0", "br" to "320k")
        )
    )

    @Test
    fun `round trip preserves all track fields`() {
        val queue = sampleQueue()
        val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(queue))
        assertEquals(queue, decoded)
    }

    @Test
    fun `extra map survives serialization`() {
        val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(sampleQueue()))!!
        assertEquals(mapOf("grey" to "0", "br" to "320k"), decoded[1].extra)
        assertEquals("migu:2", decoded[1].trackId)
    }

    @Test
    fun `corrupt json returns null instead of throwing`() {
        assertNull(PlaybackSessionCodec.decode("not json {"))
        assertNull(PlaybackSessionCodec.decode("123"))
        assertNull(PlaybackSessionCodec.decode("[{\"platform\": 1}]"))
    }

    @Test
    fun `empty queue encodes to empty list`() {
        val decoded = PlaybackSessionCodec.decode(PlaybackSessionCodec.encode(emptyList()))!!
        assertTrue(decoded.isEmpty())
    }
}
