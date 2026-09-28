package dev.jaronwilson.modes.remote

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Base64
import dev.jaronwilson.modes.notify.NotificationGate
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * What is playing on the phone, and control of it, for Odysseus' music bar
 * when it is open on the phone. Asked for: "I want the mobile version to work
 * with the music too; I have it open on the phone and it's playing on the
 * phone, but I only see the PC's".
 *
 * Android only shows other apps' media sessions to an app holding notification
 * access, which the Modes notification listener (NotificationGate) already has.
 */
object MediaAccess {

    private fun controller(ctx: Context): MediaController? {
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return null
        val sessions = msm.getActiveSessions(ComponentName(ctx, NotificationGate::class.java))
        val i = MediaMath.pick(sessions.map { it.playbackState?.state == PlaybackState.STATE_PLAYING })
            ?: return null
        return sessions[i]
    }

    fun nowPlaying(ctx: Context): JSONObject {
        val c = controller(ctx)
            ?: return JSONObject().put("ok", true).put("playing", false)
                .put("reason", "nothing is playing on the phone")
        val md = c.metadata
        val state = c.playbackState
        val out = JSONObject().put("ok", true)
            .put("playing", state?.state == PlaybackState.STATE_PLAYING)
            .put("status", stateName(state?.state))
            .put("title", md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "")
            .put("artist", md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: "")
            .put("album", md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: "")
            .put("source", c.packageName)
        md?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 }?.let { out.put("duration_ms", it) }
        state?.position?.takeIf { it >= 0 }?.let { out.put("position_ms", it) }
        art(md)?.let { out.put("art_jpeg_b64", it) }
        return out
    }

    fun control(ctx: Context, action: String): JSONObject {
        val c = controller(ctx)
            ?: return JSONObject().put("ok", false).put("error", "nothing is playing on the phone")
        val t = c.transportControls
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        when (action) {
            "play_pause" -> if (playing) t.pause() else t.play()
            "play" -> t.play()
            "pause" -> t.pause()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            "stop" -> t.stop()
        }
        return JSONObject().put("ok", true).put("action", action).put("app", c.packageName)
    }

    fun volume(ctx: Context): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return JSONObject().put("ok", true)
            .put("volume", MediaMath.indexToPercent(am.getStreamVolume(AudioManager.STREAM_MUSIC), max))
            .put("muted", am.isStreamMute(AudioManager.STREAM_MUSIC))
    }

    fun setVolume(ctx: Context, percent: Int): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (percent > 0 && am.isStreamMute(AudioManager.STREAM_MUSIC)) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
        }
        am.setStreamVolume(AudioManager.STREAM_MUSIC, MediaMath.percentToIndex(percent, max), 0)
        return volume(ctx)
    }

    fun setMute(ctx: Context, muted: Boolean): JSONObject {
        val am = ctx.getSystemService(AudioManager::class.java)
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0)
        return volume(ctx)
    }

    private fun stateName(s: Int?): String = when (s) {
        PlaybackState.STATE_PLAYING -> "Playing"
        PlaybackState.STATE_PAUSED -> "Paused"
        PlaybackState.STATE_STOPPED -> "Stopped"
        PlaybackState.STATE_BUFFERING -> "Buffering"
        else -> "Unknown"
    }

    /** The player's own album art, small, for the bar. */
    private fun art(md: MediaMetadata?): String? {
        val bmp = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART) ?: return null
        return try {
            val side = 160
            val scaled = Bitmap.createScaledBitmap(bmp, side, side, true)
            val buf = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, buf)
            Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }
    }
}
