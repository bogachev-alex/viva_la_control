package viva.la.circle.remap

import android.accessibilityservice.AccessibilityService
import android.content.Context

/**
 * Deep FireTargetAction entry: Remap session, Shutter, and chooser share this path.
 * Discovery / diag helpers remain on ActionExecutionEngine but are not this interface.
 */
object TargetActionFire {
    fun forService(
        context: Context,
        service: AccessibilityService? = null,
    ): FireTargetAction = EngineFireTargetAction(context, service)
}
