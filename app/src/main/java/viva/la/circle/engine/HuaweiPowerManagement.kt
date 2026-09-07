package viva.la.circle.engine

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings

/**
 * Huawei kills accessibility services unless App launch is set to "Manage manually"
 * (auto-launch + secondary launch + run in background). These intents are best-effort —
 * EMUI / HarmonyOS versions rename the screens often.
 */
object HuaweiPowerManagement {

    const val SYSTEM_MANAGER_PACKAGE = "com.huawei.systemmanager"

    fun isHuaweiDevice(profile: VendorProfile = VendorProfile.current()): Boolean =
        profile.id == VendorProfile.HUAWEI.id

    /**
     * Explicit components first (try/catch, no resolve pre-check). Implicit action next,
     * only if it resolves to a real activity — not HwResolverActivity. Details page last.
     */
    fun openAppLaunchSettings(context: Context): Boolean {
        val pm = context.packageManager
        for (intent in explicitAppLaunchIntents()) {
            if (startExplicit(context, intent)) return true
        }
        for (intent in implicitAppLaunchIntents()) {
            if (!resolvesToRealActivity(pm, intent)) continue
            if (startExplicit(context, intent)) return true
        }
        return startExplicit(context, detailsSettingsIntent(context.packageName))
    }

    fun explicitAppLaunchIntents(): List<Intent> {
        return listOf(
            componentIntent(
                "com.huawei.systemmanager.optimize.process.ProtectActivity",
            ),
            componentIntent(
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
            ),
            componentIntent(
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        )
    }

    fun implicitAppLaunchIntents(): List<Intent> {
        return listOf(Intent("huawei.intent.action.HSM_BOOTAPP_MANAGER"))
    }

    fun detailsSettingsIntent(packageName: String): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$packageName")
        }
    }

    fun appLaunchIntents(packageName: String): List<Intent> {
        return explicitAppLaunchIntents() + implicitAppLaunchIntents() +
            listOf(detailsSettingsIntent(packageName))
    }

    fun resolvesToRealActivity(pm: PackageManager, intent: Intent): Boolean {
        val resolved = try {
            intent.resolveActivity(pm)
        } catch (_: Exception) {
            null
        } ?: return false
        return !ActionExecutionEngine.isAssistDisambiguation(
            resolved.packageName,
            resolved.className,
        )
    }

    private fun componentIntent(className: String): Intent {
        return Intent().setComponent(ComponentName(SYSTEM_MANAGER_PACKAGE, className))
    }

    private fun startExplicit(context: Context, intent: Intent): Boolean {
        return try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
