package dev.jaronwilson.modes.dream

import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.jaronwilson.modes.AppGraph
import dev.jaronwilson.modes.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The closest an app can get to an always-on display.
 *
 * No app can replace Android's real always-on display: that belongs to the
 * system, and there is no API for it at any permission level. What Android
 * does hand out is the screensaver slot, which runs while the phone is
 * charging or docked and is genuinely always on for as long as it is. Set
 * under Settings > Display > Screen saver.
 *
 * Dim, static and sparse on purpose. This is meant to be read from across a
 * desk at night, not looked at.
 */
class ModesDream : DreamService() {

    private val ticker = Handler(Looper.getMainLooper())
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var mode: TextView
    private lateinit var agenda: TextView

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            // Once a minute is enough, and it is the difference between a
            // screensaver and a battery drain.
            ticker.postDelayed(this, 60_000)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        AppGraph.ensure(applicationContext)
        isInteractive = false
        isFullscreen = true
        // Dimmed rather than bright: this runs all night on a bedside table.
        isScreenBright = false

        val serif = runCatching { ResourcesCompat.getFont(this, R.font.fraunces) }.getOrNull()
        val sans = runCatching { ResourcesCompat.getFont(this, R.font.inter) }.getOrNull()

        clock = TextView(this).apply {
            setTextColor(Color.parseColor("#EDE9E0"))
            textSize = 62f
            typeface = serif ?: Typeface.SERIF
        }
        date = TextView(this).apply {
            setTextColor(Color.parseColor("#A39D8F"))
            textSize = 15f
            typeface = sans
        }
        mode = TextView(this).apply {
            setTextColor(Color.parseColor("#D97A4A"))
            textSize = 13f
            letterSpacing = 0.18f
            typeface = sans
            setPadding(0, 26, 0, 0)
        }
        agenda = TextView(this).apply {
            setTextColor(Color.parseColor("#A39D8F"))
            textSize = 16f
            typeface = sans
            setLineSpacing(10f, 1f)
            setPadding(0, 22, 0, 0)
        }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                setBackgroundColor(Color.BLACK)
                setPadding(72, 0, 72, 0)
                addView(clock)
                addView(date)
                addView(mode)
                addView(agenda)
            }
        )
        refresh()
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        ticker.post(tick)
    }

    override fun onDreamingStopped() {
        ticker.removeCallbacks(tick)
        super.onDreamingStopped()
    }

    private fun refresh() {
        val now = LocalDateTime.now()
        clock.text = now.format(DateTimeFormatter.ofPattern("H:mm"))
        date.text = now.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))

        runCatching {
            runBlocking {
                val active = AppGraph.repo.settings.active.first()
                val m = AppGraph.repo.mode(active.modeId)
                mode.text = "${m?.glyph.orEmpty()} ${m?.name.orEmpty()}".trim().uppercase()

                val millis = System.currentTimeMillis()
                val events = AppGraph.scheduler.calendar
                    .events(millis, millis + 14 * 60 * 60 * 1000L)
                    .filter { it.end > millis }
                    .sortedBy { it.begin }
                    .take(3)
                val fmt = DateTimeFormatter.ofPattern("H:mm")
                agenda.text = events.joinToString("\n") { event ->
                    val at = if (event.allDay) "all day"
                    else fmt.format(Instant.ofEpochMilli(event.begin).atZone(ZoneId.systemDefault()))
                    "$at   ${event.title}"
                }
            }
        }
    }
}
