package viva.la.circle.model

/**
 * BlueLM TargetAction prefs decode + Remap gate.
 *
 * Unset (null) = not configured yet. [TargetAction.DEFAULT_ASSISTANT] in prefs migrates to unset
 * (footgun: system default can be the Intercepted assistant). [TargetAction.NONE] is an explicit
 * Pass-through pick.
 */
object BlueLMActionConfig {
    fun decodeStored(raw: String?): TargetAction? {
        if (raw.isNullOrBlank()) return null
        if (raw == TargetAction.DEFAULT_ASSISTANT.name) return null
        return TargetAction.entries.firstOrNull { it.name == raw }
    }

    fun shouldMigrateDefaultAssistant(raw: String?): Boolean =
        raw == TargetAction.DEFAULT_ASSISTANT.name

    fun isConfigured(action: TargetAction?): Boolean = action != null

    fun shouldRemap(action: TargetAction?): Boolean =
        action != null && action != TargetAction.NONE
}
