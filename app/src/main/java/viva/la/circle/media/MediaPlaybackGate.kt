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
            // NLS on but nothing Playing — trust sessions over coarse isMusicActive.
            return false
        }
        @Suppress("DEPRECATION")
        return audioManager.isMusicActive
    }

    fun skipNext(context: Context): Boolean = dispatchSkip(context, next = true)

    fun skipPrevious(context: Context): Boolean = dispatchSkip(context, next = false)

    fun adjustMusicVolume(context: Context, raise: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val direction = if (raise) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI,
        )
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
