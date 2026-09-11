package viva.la.circle.media

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.KeyEvent
import viva.la.circle.service.MediaNotificationListener

/**
 * Detects active media playback and dispatches next/previous.
 * Prefers MediaSession when notification listener is enabled; else [AudioManager.isMusicActive]
 * + synthetic media keys.
 */
object MediaPlaybackGate {

    /** Captured music / session volume so a long-press skip can undo system ramp. */
    data class VolumeSnapshot(
        val streamVolume: Int,
        val streamMax: Int,
        val sessionVolume: Int? = null,
        val sessionMax: Int? = null,
    )

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val expected = MediaNotificationListener.componentName(context)
        val flat = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(flat)
        while (splitter.hasNext()) {
            val raw = splitter.next()
            val cn = ComponentName.unflattenFromString(raw) ?: continue
            if (cn == expected) return true
        }
        return false
    }

    fun isInCall(audioManager: AudioManager): Boolean {
        // MODE_RINGING = 1; avoid relying on SDK constant visibility.
        return when (audioManager.mode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            1,
            -> true
            else -> false
        }
    }

    fun isMediaPlaying(context: Context): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (isNotificationListenerEnabled(context)) {
            val sessions = activeControllers(context)
            if (sessions.any { isPlaying(it) }) return true
            // NLS on but no Playing session (OEM / player quirks) — fall back to coarse signal.
        }
        @Suppress("DEPRECATION")
        return audioManager.isMusicActive
    }

    fun skipNext(context: Context): Boolean = dispatchSkip(context, next = true)

    fun skipPrevious(context: Context): Boolean = dispatchSkip(context, next = false)

    fun captureVolumeSnapshot(context: Context): VolumeSnapshot {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val stream = AudioManager.STREAM_MUSIC
        val streamVolume = audioManager.getStreamVolume(stream)
        val streamMax = audioManager.getStreamMaxVolume(stream)
        var sessionVolume: Int? = null
        var sessionMax: Int? = null
        if (isNotificationListenerEnabled(context)) {
            val controller = pickSkipController(context) ?: activeControllers(context).firstOrNull()
            val info = controller?.playbackInfo
            if (info != null && info.volumeControl == 2 /* VOLUME_CONTROL_ABSOLUTE */) {
                sessionVolume = info.currentVolume
                sessionMax = info.maxVolume
            }
        }
        return VolumeSnapshot(
            streamVolume = streamVolume,
            streamMax = streamMax,
            sessionVolume = sessionVolume,
            sessionMax = sessionMax,
        )
    }

    fun restoreVolumeSnapshot(context: Context, snapshot: VolumeSnapshot): Boolean {
        var restored = false
        if (isNotificationListenerEnabled(context) &&
            snapshot.sessionVolume != null &&
            snapshot.sessionMax != null
        ) {
            val controller = pickSkipController(context) ?: activeControllers(context).firstOrNull()
            if (controller != null) {
                try {
                    val target = snapshot.sessionVolume.coerceIn(0, snapshot.sessionMax)
                    controller.setVolumeTo(target, 0)
                    restored = true
                } catch (_: Exception) {
                }
            }
        }
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val target = snapshot.streamVolume.coerceIn(0, snapshot.streamMax)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            true
        } catch (_: Exception) {
            restored
        }
    }

    fun adjustMusicVolume(context: Context, raise: Boolean): Boolean {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val flags = AudioManager.FLAG_SHOW_UI

        // Prefer the playing session's volume (Apple Music / OEM players often ignore STREAM_MUSIC).
        if (isNotificationListenerEnabled(context)) {
            val controller = pickSkipController(context) ?: activeControllers(context).firstOrNull()
            if (controller != null) {
                try {
                    controller.adjustVolume(direction, flags)
                    return true
                } catch (_: Exception) {
                }
            }
        }

        // Suggested stream follows whatever the system volume UI would target.
        try {
            @Suppress("DEPRECATION")
            audioManager.adjustSuggestedStreamVolume(
                direction,
                AudioManager.USE_DEFAULT_STREAM_TYPE,
                flags,
            )
            return true
        } catch (_: Exception) {
        }

        return try {
            val stream = AudioManager.STREAM_MUSIC
            val cur = audioManager.getStreamVolume(stream)
            val max = audioManager.getStreamMaxVolume(stream)
            val next = (cur + if (raise) 1 else -1).coerceIn(0, max)
            if (next == cur) {
                audioManager.adjustStreamVolume(stream, direction, flags)
            } else {
                audioManager.setStreamVolume(stream, next, flags)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun dispatchSkip(context: Context, next: Boolean): Boolean {
        if (isNotificationListenerEnabled(context)) {
            val controller = pickSkipController(context)
            if (controller != null) {
                if (next) controller.transportControls.skipToNext()
                else controller.transportControls.skipToPrevious()
                return true
            }
        }
        return injectMediaKey(
            context,
            if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        )
    }

    private fun pickSkipController(context: Context): MediaController? {
        val playing = activeControllers(context).filter { isPlaying(it) }
        if (playing.isEmpty()) return null
        val withSkip = playing.filter { canSkip(it) }
        val pool = withSkip.ifEmpty { playing }
        return pool.maxByOrNull { it.playbackState?.lastPositionUpdateTime ?: 0L }
    }

    private fun activeControllers(context: Context): List<MediaController> {
        return try {
            val mgr = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            mgr.getActiveSessions(MediaNotificationListener.componentName(context))
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun isPlaying(controller: MediaController): Boolean {
        val state = controller.playbackState?.state ?: return false
        return state == PlaybackState.STATE_PLAYING
    }

    private fun canSkip(controller: MediaController): Boolean {
        val actions = controller.playbackState?.actions ?: return false
        val next = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L
        val prev = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L
        return next || prev
    }

    private fun injectMediaKey(context: Context, keyCode: Int): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val up = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            audioManager.dispatchMediaKeyEvent(down)
            audioManager.dispatchMediaKeyEvent(up)
            true
        } catch (_: Exception) {
            false
        }
    }
}
