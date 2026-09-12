package viva.la.circle.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.annotation.StringRes
import viva.la.circle.R
import viva.la.circle.model.TargetAction
import viva.la.circle.service.InterceptorStateRepository

/**
 * Circle to Search. Prefer a VoiceInteraction session with screenshot of the current
 * app (overlay). Fall back to SearchManager.launchAssist, which goes through StatusBar
 * and on OriginOS can HOME the current task if a finger is still on the nav region.
 * The Google app reads omni.entry_point and opens LensientActivity instead of the
 * ordinary assistant. Path idea from MiCTS (GPL-3.0); this implementation is original.
 */
object CircleToSearch {

    const val CTS_SETTLE_MS = 250
    const val GOOGLE_PACKAGE = "com.google.android.googlequicksearchbox"

    const val KEY_ENTRY_POINT = "omni.entry_point"
    const val KEY_INVOCATION_TIME = "invocation_time_ms"
    const val DEFAULT_ENTRY_POINT = 1

    /**
     * VoiceInteractionSession.SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT |
     * SHOW_SOURCE_APPLICATION. Application source skips the OEM assist-gesture
     * animation that can flash the launcher.
     */
    const val SHOW_SESSION_FLAGS = 1 or 2 or 4

    data class Readiness(
        val googleInstalled: Boolean,
        val googleIsAssistant: Boolean,
        val contextualSearchKey: String?,
        val serviceAvailable: Boolean,
    ) {
        val usable: Boolean get() = serviceAvailable && googleInstalled &&
            (googleIsAssistant || !contextualSearchKey.isNullOrEmpty())

        @get:StringRes
        val blockerRes: Int? get() = when {
            !serviceAvailable -> R.string.cts_blocker_service_unavailable
            !googleInstalled -> R.string.cts_blocker_google_not_installed
            !googleIsAssistant && contextualSearchKey.isNullOrEmpty() ->
                R.string.cts_blocker_google_not_assistant
            else -> null
        }

        /** English tag for diag logs; UI should use [localizedBlocker]. */
        val blocker: String? get() = when {
            !serviceAvailable -> "voiceinteraction service unavailable"
            !googleInstalled -> "Google app not installed"
            !googleIsAssistant && contextualSearchKey.isNullOrEmpty() ->
                "Google is not the default assistant"
            else -> null
        }

        fun localizedBlocker(context: Context): String? =
            blockerRes?.let { context.getString(it) }
    }

    fun extraSettleMs(action: TargetAction): Int {
        return if (action == TargetAction.CIRCLE_TO_SEARCH) CTS_SETTLE_MS else 0
    }

    fun isGoogleAssistantSetting(raw: String?): Boolean {
        val pkg = raw?.substringBefore('/')?.trim().orEmpty()
        return pkg == GOOGLE_PACKAGE
    }

    fun assistantSettingsActionCandidates(sdkInt: Int): List<String> {
        val actions = mutableListOf<String>()
        if (sdkInt >= 29) {
            actions += "android.settings.ASSIST_GESTURE_SETTINGS"
        }
        actions += Settings.ACTION_VOICE_INPUT_SETTINGS
        if (sdkInt >= 24) {
            actions += Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS
        }
        actions += Settings.ACTION_APPLICATION_SETTINGS
        return actions
    }

    fun probe(context: Context): Readiness {
        val googleInstalled = isGoogleInstalled(context)
        val googleIsAssistant = isGoogleDefaultAssistant(context)
        val contextualSearchKey = readContextualSearchKey()
        val serviceAvailable = ActionExecutionEngine.hasLaunchAssist(context)
        val readiness = Readiness(
            googleInstalled = googleInstalled,
            googleIsAssistant = googleIsAssistant,
            contextualSearchKey = contextualSearchKey,
            serviceAvailable = serviceAvailable,
        )
        InterceptorStateRepository.diag(
            "CTS",
            "probe usable=${readiness.usable} googleInstalled=$googleInstalled " +
                "googleIsAssistant=$googleIsAssistant csKey=${contextualSearchKey ?: "(empty)"} " +
                "service=$serviceAvailable blocker=${readiness.blocker ?: "none"}",
        )
        return readiness
    }

    fun trigger(context: Context, entryPoint: Int = DEFAULT_ENTRY_POINT): Boolean {
        val args = sessionArgs(entryPoint)
        return triggerWith(
            entryPoint = entryPoint,
            showSession = {
                ActionExecutionEngine.invokeVoiceInteractionSession(
                    context,
                    args,
                    SHOW_SESSION_FLAGS,
                )
            },
            launchAssist = {
                ActionExecutionEngine.invokeSystemAssistGesture(context, args)
            },
        )
    }

    fun triggerWith(
        entryPoint: Int,
        showSession: () -> Boolean,
        launchAssist: () -> Boolean,
    ): Boolean {
        if (showSession()) {
            InterceptorStateRepository.diag("CTS", "trigger via showSession entry=$entryPoint")
            return true
        }
        if (launchAssist()) {
            InterceptorStateRepository.diag("CTS", "trigger via launchAssist entry=$entryPoint")
            return true
        }
        InterceptorStateRepository.diag("CTS", "trigger failed: launchAssist missed")
        return false
    }

    fun assistantSettingsIntent(pm: PackageManager, sdkInt: Int = Build.VERSION.SDK_INT): Intent? {
        for (action in assistantSettingsActionCandidates(sdkInt)) {
            val intent = Intent(action)
            val resolved = intent.resolveActivity(pm)
            if (ActionExecutionEngine.resolvesToRealActivity(
                    resolved?.packageName,
                    resolved?.className,
                )
            ) {
                return intent
            }
        }
        return null
    }

    fun sessionArgs(entryPoint: Int, invocationTimeMs: Long = System.currentTimeMillis()): Bundle {
        return Bundle().apply {
            putInt(KEY_ENTRY_POINT, entryPoint)
            putLong(KEY_INVOCATION_TIME, invocationTimeMs)
        }
    }

    private fun isGoogleInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getApplicationInfo(GOOGLE_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun isGoogleDefaultAssistant(context: Context): Boolean {
        val cr = context.contentResolver
        return isGoogleAssistantSetting(Settings.Secure.getString(cr, "assistant")) ||
            isGoogleAssistantSetting(Settings.Secure.getString(cr, "voice_interaction_service"))
    }

    private fun readContextualSearchKey(): String? {
        return try {
            val id = Resources.getSystem().getIdentifier(
                "config_defaultContextualSearchKey",
                "string",
                "android",
            )
            if (id == 0) return null
            Resources.getSystem().getString(id).takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}
