package dev.jaronwilson.modes.remote

/**
 * The parts of phone media control that are plain arithmetic and naming, kept
 * free of Android types so they can be tested on the JVM (see CommandProtocol).
 */
object MediaMath {

    /** What media_control accepts. */
    val ACTIONS = setOf("play_pause", "play", "pause", "next", "previous", "stop")

    /** 0-100 to the stream's own steps (music is usually 0..15 or 0..25). */
    fun percentToIndex(percent: Int, max: Int): Int {
        if (max <= 0) return 0
        val p = percent.coerceIn(0, 100)
        return Math.round(p * max / 100.0).toInt().coerceIn(0, max)
    }

    /** The stream's steps back to 0-100. */
    fun indexToPercent(index: Int, max: Int): Int {
        if (max <= 0) return 0
        return Math.round(index.coerceIn(0, max) * 100.0 / max).toInt()
    }

    /**
     * Which of the active sessions to show and control: the one playing, else
     * the most recent one (the list comes newest first), else none.
     */
    fun pick(playing: List<Boolean>): Int? {
        if (playing.isEmpty()) return null
        val i = playing.indexOfFirst { it }
        return if (i >= 0) i else 0
    }
}
