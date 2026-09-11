package viva.la.circle.remap

import viva.la.circle.model.TargetAction

/**
 * UI Remap-config façade: TargetAction summary, readiness blockers, Huawei need.
 * Compose maps these models to strings; discovery adapters stay injectable.
 */
object RemapConfig {

    sealed class FirePreview {
        data object Unset : FirePreview()
        data object PassThrough : FirePreview()
        data object Chooser : FirePreview()
        data object DefaultUnset : FirePreview()
        data class DefaultBlocked(val packageName: String) : FirePreview()
        data class DefaultApp(val label: String) : FirePreview()
        data object CircleToSearch : FirePreview()
        data class Hwcts(val installed: Boolean) : FirePreview()
        data object SpecificUnset : FirePreview()
        data class SpecificApp(val label: String) : FirePreview()
        data object Flashlight : FirePreview()
        data object Screenshot : FirePreview()
        data object Mute : FirePreview()
    }

    enum class HwctsGate {
        NOT_INSTALLED,
        ACCESSIBILITY_OFF,
        READY,
    }

    sealed class CtsGate {
        data object Checking : CtsGate()
        data object ReadyGoogle : CtsGate()
        data object ReadyContextual : CtsGate()
        data class Blocked(
            val blockerText: String?,
            val googleInstalled: Boolean,
            val showOpenAssistantSettings: Boolean,
            val showAssistantSettingsPath: Boolean,
        ) : CtsGate()
    }

    fun firePreview(
        action: TargetAction?,
        specificPackage: String?,
        systemDefaultPackage: String?,
        ownPackage: String,
        hwctsInstalled: Boolean,
        appLabel: (String) -> String,
    ): FirePreview {
        if (action == null) return FirePreview.Unset
        return when (action) {
            TargetAction.NONE -> FirePreview.PassThrough
            TargetAction.ASSISTANT_CHOOSER -> FirePreview.Chooser
            TargetAction.DEFAULT_ASSISTANT -> when {
                systemDefaultPackage == null -> FirePreview.DefaultUnset
                InterceptedAssistant.wouldLoopToInterceptedAssistant(
                    systemDefaultPackage,
                    ownPackage,
                ) -> FirePreview.DefaultBlocked(systemDefaultPackage)
                else -> FirePreview.DefaultApp(appLabel(systemDefaultPackage))
            }
            TargetAction.CIRCLE_TO_SEARCH -> FirePreview.CircleToSearch
            TargetAction.HWCTS -> FirePreview.Hwcts(installed = hwctsInstalled)
            TargetAction.SPECIFIC_APP ->
                if (specificPackage.isNullOrEmpty()) {
                    FirePreview.SpecificUnset
                } else {
                    FirePreview.SpecificApp(appLabel(specificPackage))
                }
            TargetAction.FLASHLIGHT -> FirePreview.Flashlight
            TargetAction.SCREENSHOT -> FirePreview.Screenshot
            TargetAction.MUTE_TOGGLE -> FirePreview.Mute
        }
    }

    /** Package to show in the Default Assistant loop warning, or null if none. */
    fun defaultAssistantLoopPackage(
        action: TargetAction?,
        systemDefaultPackage: String?,
        ownPackage: String,
    ): String? {
        if (action != TargetAction.DEFAULT_ASSISTANT) return null
        val pkg = systemDefaultPackage ?: return null
        return pkg.takeIf {
            InterceptedAssistant.wouldLoopToInterceptedAssistant(it, ownPackage)
        }
    }

    fun hwctsGate(installed: Boolean, accessibilityEnabled: Boolean): HwctsGate = when {
        !installed -> HwctsGate.NOT_INSTALLED
        !accessibilityEnabled -> HwctsGate.ACCESSIBILITY_OFF
        else -> HwctsGate.READY
    }

    fun ctsGate(
        readiness: CtsReadiness?,
        hasAssistantSettingsIntent: Boolean,
    ): CtsGate {
        if (readiness == null) return CtsGate.Checking
        if (readiness.usable && readiness.googleIsAssistant) return CtsGate.ReadyGoogle
        if (readiness.usable) return CtsGate.ReadyContextual
        val needsAssistantSettings = readiness.googleInstalled &&
            !readiness.googleIsAssistant &&
            readiness.contextualSearchKey.isNullOrEmpty()
        return CtsGate.Blocked(
            blockerText = readiness.blockerText,
            googleInstalled = readiness.googleInstalled,
            showOpenAssistantSettings = needsAssistantSettings && hasAssistantSettingsIntent,
            showAssistantSettingsPath = needsAssistantSettings && !hasAssistantSettingsIntent,
        )
    }

    fun needsHuaweiAppLaunchCard(isHuaweiDevice: Boolean, acknowledged: Boolean): Boolean =
        isHuaweiDevice && !acknowledged
}

/** Context-free CtS readiness slice for [RemapConfig.ctsGate]. */
data class CtsReadiness(
    val usable: Boolean,
    val googleInstalled: Boolean,
    val googleIsAssistant: Boolean,
    val contextualSearchKey: String?,
    val blockerText: String?,
)
