package viva.la.circle.model

import androidx.annotation.StringRes
import viva.la.circle.R

enum class TargetAction(
    @param:StringRes val titleRes: Int,
    @param:StringRes val descriptionRes: Int,
) {
    NONE(
        titleRes = R.string.action_none_title,
        descriptionRes = R.string.action_none_description,
    ),
    ASSISTANT_CHOOSER(
        titleRes = R.string.action_chooser_title,
        descriptionRes = R.string.action_chooser_description,
    ),
    DEFAULT_ASSISTANT(
        titleRes = R.string.action_default_title,
        descriptionRes = R.string.action_default_description,
    ),
    CIRCLE_TO_SEARCH(
        titleRes = R.string.action_cts_title,
        descriptionRes = R.string.action_cts_description,
    ),
    HWCTS(
        titleRes = R.string.action_hwcts_title,
        descriptionRes = R.string.action_hwcts_description,
    ),
    SPECIFIC_APP(
        titleRes = R.string.action_specific_title,
        descriptionRes = R.string.action_specific_description,
    ),
    FLASHLIGHT(
        titleRes = R.string.action_flashlight_title,
        descriptionRes = R.string.action_flashlight_description,
    ),
    SCREENSHOT(
        titleRes = R.string.action_screenshot_title,
        descriptionRes = R.string.action_screenshot_description,
    ),
    MUTE_TOGGLE(
        titleRes = R.string.action_mute_title,
        descriptionRes = R.string.action_mute_description,
    ),
    /** Navigate Home — for Gesture Handle swipe Remap, not BlueLM/Shutter pickers. */
    HOME(
        titleRes = R.string.action_home_title,
        descriptionRes = R.string.action_home_description,
    ),
    /** Navigate Back — for Gesture Handle horizontal swipe Remap. */
    BACK(
        titleRes = R.string.action_back_title,
        descriptionRes = R.string.action_back_description,
    );

    companion object {
        fun fromName(name: String?, default: TargetAction = DEFAULT_ASSISTANT): TargetAction {
            if (name == null) return default
            return entries.firstOrNull { it.name == name } ?: default
        }

        /** TargetActions shown for BlueLM / Shutter (excludes nav-only [HOME]/[BACK]). */
        fun triggerEntries(): List<TargetAction> =
            entries.filter { it != HOME && it != BACK }

        /** TargetActions for Gesture Handle slot pickers (includes [HOME]/[BACK]). */
        fun gestureHandleEntries(): List<TargetAction> = entries.toList()
    }
}
