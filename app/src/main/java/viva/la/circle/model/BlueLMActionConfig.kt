package viva.la.circle.model

/**
 * BlueLM TargetAction prefs decode + Remap gate.
 *
 * Unset (null) = not configured yet. [TargetAction.NONE] is an explicit Pass-through pick.
 * [TargetAction.DEFAULT_ASSISTANT] is a real TargetAction and must persist across reloads.
 */
object BlueLMActionConfig {
    fun decodeStored(raw: String?): TargetAction? {
        if (raw.isNullOrBlank()) return null
        return TargetAction.entries.firstOrNull { it.name == raw }
    }

    fun isConfigured(action: TargetAction?): Boolean = action != null

    fun shouldRemap(action: TargetAction?): Boolean =
        action != null && action != TargetAction.NONE
}
