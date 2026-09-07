package viva.la.circle.model

enum class TargetAction(val title: String, val description: String) {
    NONE(
        title = "Do Nothing (Disabled)",
        description = "Disable interception for this button",
    ),
    ASSISTANT_CHOOSER(
        title = "Assistant Chooser Menu",
        description = "Opens chooser dialog with installed voice assistants"
    ),
    DEFAULT_ASSISTANT(
        title = "Default Assistant",
        description = "Same as corner-swipe / long-press Home (system assist gesture)"
    ),
    CIRCLE_TO_SEARCH(
        title = "Circle to Search",
        description = "Google's screen search overlay (needs Google app as assistant)"
    ),
    HWCTS(
        title = "HwCTS",
        description = "HwCTS circle search on the current screen"
    ),
    SPECIFIC_APP(
        title = "Specific App",
        description = "Launches user-selected package name"
    ),
    FLASHLIGHT(
        title = "Toggle Flashlight",
        description = "Toggles camera torch"
    ),
    SCREENSHOT(
        title = "Take Screenshot",
        description = "Runs GLOBAL_ACTION_TAKE_SCREENSHOT"
    ),
    MUTE_TOGGLE(
        title = "Mute / Unmute",
        description = "Toggles media stream volume"
    );

    companion object {
        fun fromName(name: String?, default: TargetAction = DEFAULT_ASSISTANT): TargetAction {
            if (name == null) return default
            return entries.firstOrNull { it.name == name } ?: default
        }
    }
}
