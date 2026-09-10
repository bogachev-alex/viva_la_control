package viva.la.circle.remap

import android.accessibilityservice.AccessibilityService
import android.content.Context
import viva.la.circle.engine.ActionExecutionEngine
import viva.la.circle.model.TargetAction

/** Production FireTargetAction adapter over ActionExecutionEngine. */
class EngineFireTargetAction(
    private val context: Context,
    private val service: AccessibilityService? = null,
) : FireTargetAction {
    override fun fire(action: TargetAction, specificPackage: String?): Boolean {
        return ActionExecutionEngine.executeAction(
            context = context,
            action = action,
            specificPackage = specificPackage,
            service = service,
        )
    }
}
