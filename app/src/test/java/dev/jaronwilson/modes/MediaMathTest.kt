package dev.jaronwilson.modes

import dev.jaronwilson.modes.remote.CommandProtocol
import dev.jaronwilson.modes.remote.MediaMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaMathTest {

    @Test fun percentMapsOntoTheStreamSteps() {
        assertEquals(0, MediaMath.percentToIndex(0, 15))
        assertEquals(15, MediaMath.percentToIndex(100, 15))
        assertEquals(8, MediaMath.percentToIndex(50, 15))
        assertEquals(15, MediaMath.percentToIndex(250, 15))      // clamped
        assertEquals(0, MediaMath.percentToIndex(-5, 15))
        assertEquals(0, MediaMath.percentToIndex(50, 0))         // no stream
    }

    @Test fun stepsMapBackToPercent() {
        assertEquals(53, MediaMath.indexToPercent(8, 15))
        assertEquals(100, MediaMath.indexToPercent(25, 25))
        assertEquals(0, MediaMath.indexToPercent(3, 0))
    }

    @Test fun thePlayingSessionWinsElseTheNewest() {
        assertEquals(2, MediaMath.pick(listOf(false, false, true)))
        assertEquals(0, MediaMath.pick(listOf(false, false)))
        assertNull(MediaMath.pick(emptyList()))
    }

    @Test fun theListenerAcceptsTheMediaCommands() {
        for (c in listOf("now_playing", "media_control", "get_volume", "set_volume", "set_mute")) {
            assertTrue(c, c in CommandProtocol.SUPPORTED)
        }
        assertTrue("play_pause" in MediaMath.ACTIONS && "next" in MediaMath.ACTIONS)
    }
}
