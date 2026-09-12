package viva.la.circle.media

import android.content.Context
import android.content.pm.PackageManager
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import java.lang.reflect.Proxy

/**
 * System-level volume long-press hook.
 *
 * With the [PERMISSION] granted (via `adb shell pm grant`, exactly like MacroDroid's
 * "ADB hack"), the framework routes a **long-press** of a volume key to our callback
 * and does **not** change the volume — while short presses keep native volume. This
 * sidesteps the OriginOS volume hardening entirely (the system, not us, owns the key)
 * and works with the screen off.
 *
 * The API [MediaSessionManager.setOnVolumeKeyLongPressListener] is a hidden SystemApi,
 * so it is bound reflectively and the listener interface is implemented via a [Proxy].
 */
class VolumeLongPressListener(
    private val context: Context,
    private val onLongPressDown: (keyCode: Int) -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private var listenerProxy: Any? = null
    private var registered = false

    fun isPermissionGranted(): Boolean =
        context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** @return true if the system accepted the listener. */
    fun register(): Boolean {
        if (registered) return true
        if (!isPermissionGranted()) return false
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java) ?: return false
            val listenerCls = Class.forName(LISTENER_CLASS_NAME)
            val proxy = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(listenerCls),
            ) { _, method, args ->
                when (method.name) {
                    "onVolumeKeyLongPress" -> {
                        (args?.getOrNull(0) as? KeyEvent)?.let(::dispatch)
                        null
                    }
                    "hashCode" -> System.identityHashCode(this)
                    "equals" -> (args?.getOrNull(0) === this)
                    "toString" -> "VolumeLongPressListenerProxy"
                    else -> null
                }
            }
            val setter = MediaSessionManager::class.java.getMethod(
                "setOnVolumeKeyLongPressListener",
                listenerCls,
                Handler::class.java,
            )
            setter.invoke(msm, proxy, handler)
            listenerProxy = proxy
            registered = true
            true
        } catch (_: Throwable) {
            registered = false
            listenerProxy = null
            false
        }
    }

    fun unregister() {
        if (!registered) return
        try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val listenerCls = Class.forName(LISTENER_CLASS_NAME)
            val setter = MediaSessionManager::class.java.getMethod(
                "setOnVolumeKeyLongPressListener",
                listenerCls,
                Handler::class.java,
            )
            setter.invoke(msm, null, null)
        } catch (_: Throwable) {
        } finally {
            registered = false
            listenerProxy = null
        }
    }

    val isRegistered: Boolean get() = registered

    /** Fire once at the start of the long-press (ignore repeats and the release). */
    private fun dispatch(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        if (event.repeatCount != 0) return
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return
        onLongPressDown(keyCode)
    }

    companion object {
        const val PERMISSION = "android.permission.SET_VOLUME_KEY_LONG_PRESS_LISTENER"
        private const val LISTENER_CLASS_NAME =
            "android.media.session.MediaSessionManager\$OnVolumeKeyLongPressListener"
    }
}
