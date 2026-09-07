package viva.la.circle.engine

import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import viva.la.circle.R
import viva.la.circle.model.TargetAction
import viva.la.circle.service.BlueLMInterceptorService
import viva.la.circle.service.InterceptorStateRepository
import viva.la.circle.ui.AssistantChooserActivity

data class AssistantAppInfo(
    val packageName: String,
    val label: String,
    val isInstalled: Boolean = false,
    val isDefault: Boolean = false,
)

data class AppInfo(
    val packageName: String,
    val label: String,
)

object ActionExecutionEngine {

    private const val TAG = "ActionExecutionEngine"

    val KNOWN_ASSISTANT_PACKAGES = listOf(
        "com.google.android.googlequicksearchbox" to "Google Assistant",
        "com.google.android.apps.bard" to "Gemini",
        "com.google.android.apps.gemini" to "Gemini",
        "com.openai.chatgpt" to "ChatGPT",
        "com.anthropic.claude" to "Claude",
        "com.anthropic.chat" to "Claude",
        "com.microsoft.copilot" to "Microsoft Copilot",
        "ai.perplexity.app" to "Perplexity",
    )

    const val HWCTS_PACKAGE = "com.tosharoki.hwcts"
    const val HWCTS_TILE_ACTION = "com.tosharoki.hwcts.ACTION_TILE_TRIGGER"


    private var isTorchOn = false
    private var torchCameraId: String? = null
    private var torchCallbackRegistered = false
    private var lastMusicVolume = -1

    fun getInstalledAssistants(context: Context): List<AssistantAppInfo> {
        val pm = context.packageManager
        val defaultPkg = systemDefaultAssistantPackage(context)
        val result = mutableMapOf<String, AssistantAppInfo>()

        for ((pkgName, defaultLabel) in KNOWN_ASSISTANT_PACKAGES) {
            try {
                val appInfo = pm.getApplicationInfo(pkgName, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                result[pkgName] = AssistantAppInfo(
                    packageName = pkgName,
                    label = if (label.isNotEmpty()) label else defaultLabel,
                    isInstalled = true,
                    isDefault = pkgName == defaultPkg,
                )
            } catch (_: Exception) {
                if (!result.containsKey(pkgName)) {
                    result[pkgName] = AssistantAppInfo(
                        packageName = pkgName,
                        label = defaultLabel,
                        isInstalled = false,
                        isDefault = pkgName == defaultPkg,
                    )
                }
            }
        }

        val assistantActivityIntents = listOf(
            Intent(Intent.ACTION_VOICE_COMMAND),
            Intent(Intent.ACTION_ASSIST),
        )

        for (intent in assistantActivityIntents) {
            try {
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                for (info in resolveInfos) {
                    val pkgName = info.activityInfo.packageName
                    addDiscoveredAssistant(result, pm, pkgName, info.loadLabel(pm).toString(), defaultPkg)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying intent $intent", e)
            }
        }

        // VoiceInteractionService is a *service*, not an activity. queryIntentActivities never
        // sees HwCTS's StubAssistantService, so the system default used to vanish from the list.
        try {
            val visIntent = Intent("android.service.voice.VoiceInteractionService")
            for (info in pm.queryIntentServices(visIntent, 0)) {
                val pkgName = info.serviceInfo.packageName
                addDiscoveredAssistant(result, pm, pkgName, info.loadLabel(pm).toString(), defaultPkg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying VoiceInteractionService", e)
        }

        return result.values.toList().sortedWith(
            compareByDescending<AssistantAppInfo> { it.isInstalled }
                .thenByDescending { it.isDefault }
                .thenBy { it.label },
        )
    }

    private fun addDiscoveredAssistant(
        result: MutableMap<String, AssistantAppInfo>,
        pm: PackageManager,
        pkgName: String,
        loadedLabel: String,
        defaultPkg: String?,
    ) {
        if (pkgName.isEmpty() || result.containsKey(pkgName)) return
        val label = loadedLabel.ifEmpty {
            try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
            } catch (_: Exception) {
                pkgName
            }
        }
        result[pkgName] = AssistantAppInfo(
            packageName = pkgName,
            label = label.ifEmpty { pkgName },
            isInstalled = true,
            isDefault = pkgName == defaultPkg,
        )
    }

    fun getInstalledLaunchableApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = mutableListOf<AppInfo>()
        try {
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            for (info in resolveInfos) {
                val pkgName = info.activityInfo.packageName
                val label = info.loadLabel(pm).toString()
                apps.add(AppInfo(packageName = pkgName, label = label))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching installed apps", e)
        }
        return apps.distinctBy { it.packageName }.sortedBy { it.label }
    }

    fun executeAction(
        context: Context,
        action: TargetAction,
        specificPackage: String? = null,
        service: AccessibilityService? = null,
    ): Boolean {
        Log.i(TAG, "Executing action: $action, specificPkg: $specificPackage")
        return when (action) {
            TargetAction.NONE -> {
                Log.i(TAG, "TargetAction.NONE - no action executed")
                true
            }

            TargetAction.ASSISTANT_CHOOSER -> {
                openAssistantChooser(context)
            }

            TargetAction.DEFAULT_ASSISTANT -> {
                launchDefaultAssistant(context)
            }

            TargetAction.CIRCLE_TO_SEARCH -> {
                val ok = CircleToSearch.trigger(context)
                if (!ok) {
                    val readiness = CircleToSearch.probe(context)
                    val blocker = readiness.localizedBlocker(context)
                        ?: context.getString(R.string.cts_not_ready)
                    Toast.makeText(context, blocker, Toast.LENGTH_LONG).show()
                    InterceptorStateRepository.diag(
                        "CTS",
                        "executeAction false: ${readiness.blocker ?: "not ready"}",
                    )
                }
                ok
            }

            TargetAction.HWCTS -> {
                launchHwcts(context)
            }

            TargetAction.SPECIFIC_APP -> {
                if (!specificPackage.isNullOrEmpty()) {
                    launchSpecificApp(context, specificPackage)
                } else {
                    launchDefaultAssistant(context)
                }
            }

            TargetAction.FLASHLIGHT -> {
                toggleFlashlight(context)
            }

            TargetAction.SCREENSHOT -> {
                if (service != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
                    } else {
                        Toast.makeText(context, "Screenshot action requires Android 9.0 (API 28)+", Toast.LENGTH_SHORT).show()
                        false
                    }
                } else {
                    Toast.makeText(context, "Screenshot action requires Accessibility Service", Toast.LENGTH_SHORT).show()
                    false
                }
            }

            TargetAction.MUTE_TOGGLE -> {
                toggleMute(context)
            }
        }
    }

    /**
     * HwCTS MainActivity is settings unless the user enabled launch-shortcut mode.
     * The QS tile broadcasts [HWCTS_TILE_ACTION] to LensAccessibilityService, which
     * screenshots and opens the circle overlay. If HwCTS holds the assistant role,
     * [invokeSystemAssistGesture] is the same path as Default Assistant.
     */
    fun launchHwcts(context: Context): Boolean {
        if (!isHwctsInstalled(context)) {
            InterceptorStateRepository.diag("ACT", "hwcts not installed")
            Toast.makeText(context, "HwCTS is not installed", Toast.LENGTH_LONG).show()
            return false
        }

        val defaultPkg = systemDefaultAssistantPackage(context)
        if (defaultPkg == HWCTS_PACKAGE && invokeSystemAssistGesture(context)) {
            InterceptorStateRepository.diag("ACT", "triggered hwcts via launchAssist")
            return true
        }

        if (!hwctsAccessibilityEnabled(enabledAccessibilityServices(context))) {
            InterceptorStateRepository.diag("ACT", "hwcts accessibility off")
            Toast.makeText(
                context,
                "Enable the HwCTS accessibility service",
                Toast.LENGTH_LONG,
            ).show()
            return false
        }

        return try {
            context.sendBroadcast(hwctsTriggerIntent())
            InterceptorStateRepository.diag("ACT", "triggered hwcts via $HWCTS_TILE_ACTION")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed HwCTS tile broadcast", e)
            InterceptorStateRepository.diag("ACT", "hwcts tile broadcast failed: ${e.message}")
            false
        }
    }

    fun hwctsTriggerIntent(): Intent {
        return Intent(HWCTS_TILE_ACTION).apply {
            setPackage(HWCTS_PACKAGE)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
    }

    fun isHwctsInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getApplicationInfo(HWCTS_PACKAGE, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun enabledAccessibilityServices(context: Context): String? {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (_: Exception) {
            null
        }
    }

    fun hwctsAccessibilityEnabled(enabledServices: String?): Boolean {
        if (enabledServices.isNullOrBlank()) return false
        return enabledServices.split(':').any { component ->
            component.substringBefore('/').equals(HWCTS_PACKAGE, ignoreCase = true)
        }
    }

    fun launchSpecificApp(context: Context, packageName: String): Boolean {
        val pm = context.packageManager

        if (packageName == "com.google.android.googlequicksearchbox" || packageName == "com.google.android.apps.googleassistant") {
            return launchGoogleAssistantApp(context)
        }

        // Ask the package for its *assistant* surface first. getLaunchIntentForPackage only opens
        // the app's main activity, which is why picking an assistant used to land on its home
        // screen instead of its voice UI — and why service-only assistants (no launcher activity)
        // failed outright and fell through to the hardcoded priority list.
        for (action in listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND)) {
            try {
                val intent = Intent(action).apply {
                    setPackage(packageName)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
                if (intent.resolveActivity(pm) != null) {
                    context.startActivity(intent)
                    InterceptorStateRepository.diag("ACT", "launched $packageName via $action")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed launching $packageName via $action", e)
            }
        }

        return try {
            val launchIntent = pm.getLaunchIntentForPackage(packageName)?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                InterceptorStateRepository.diag("ACT", "launched $packageName via launcher intent")
                true
            } else {
                InterceptorStateRepository.diag("ACT", "no launchable surface for $packageName")
                Toast.makeText(context, "App $packageName not found", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching package $packageName", e)
            InterceptorStateRepository.diag("ACT", "error launching $packageName: ${e.message}")
            Toast.makeText(context, "Could not launch app: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun launchGoogleAssistantApp(context: Context): Boolean {
        val pm = context.packageManager

        try {
            val assistantLaunchIntent = pm.getLaunchIntentForPackage("com.google.android.apps.googleassistant")?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            if (assistantLaunchIntent != null) {
                context.startActivity(assistantLaunchIntent)
                Log.i(TAG, "Launched Google Assistant via com.google.android.apps.googleassistant")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching com.google.android.apps.googleassistant", e)
        }

        try {
            val voiceSearchComponentIntent = Intent().apply {
                setClassName("com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.VoiceSearchActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            if (voiceSearchComponentIntent.resolveActivity(pm) != null) {
                context.startActivity(voiceSearchComponentIntent)
                Log.i(TAG, "Launched Google VoiceSearchActivity component")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching VoiceSearchActivity component", e)
        }

        try {
            val voiceSearchIntent = Intent("android.speech.action.VOICE_SEARCH").apply {
                setPackage("com.google.android.googlequicksearchbox")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            if (voiceSearchIntent.resolveActivity(pm) != null) {
                context.startActivity(voiceSearchIntent)
                Log.i(TAG, "Launched Google VOICE_SEARCH intent")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed launching Google VOICE_SEARCH intent", e)
        }

        return try {
            val launchIntent = pm.getLaunchIntentForPackage("com.google.android.googlequicksearchbox")?.apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            if (launchIntent != null) {
                context.startActivity(launchIntent)
                Log.i(TAG, "Launched Google Quick Search Box via getLaunchIntentForPackage")
                true
            } else {
                Toast.makeText(context, "Google Assistant app not found", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error launching Google Search Box", e)
            false
        }
    }

    /**
     * Invokes the OS default assistant the same way a corner-swipe or long-press Home does.
     *
     * SystemUI's assist gesture calls [SearchManager.launchAssist], which forwards to
     * `StatusBarManager.startAssist` — VoiceInteractionSession for role holders like HwCTS /
     * Google, or ACTION_ASSIST for activity-based assistants. That is an API call, not
     * accessibility key injection.
     *
     * Do not lead with ACTION_VOICE_COMMAND: that is Google Voice Search, not the assist gesture.
     */
    /**
     * True when launching the OS default assistant would re-open an OEM assistant we intercept
     * (Celia on Huawei, Copilot on Vivo). That is a hard loop: dismiss → assist gesture → same UI.
     */
    fun wouldLoopToInterceptedAssistant(
        systemDefaultPackage: String?,
        ownPackageName: String = "",
    ): Boolean {
        return BlueLMInterceptorService.isBlueLMOrVivoAssistant(
            systemDefaultPackage,
            null,
            ownPackageName,
        )
    }

    fun launchDefaultAssistant(context: Context): Boolean {
        val (systemDefault, source) = resolveSystemDefaultAssistant(context)
        InterceptorStateRepository.diag("ACT", "default assistant = $systemDefault (via $source)")

        if (wouldLoopToInterceptedAssistant(systemDefault, context.packageName)) {
            InterceptorStateRepository.diag(
                "ACT",
                "refusing default-assistant loop: $systemDefault is intercepted",
            )
            Toast.makeText(
                context,
                "System default is the intercepted assistant. Pick a different action.",
                Toast.LENGTH_LONG,
            ).show()
            return openAssistantChooser(context)
        }

        if (invokeSystemAssistGesture(context)) {
            return true
        }
        if (startUnscopedAssistIntent(context)) {
            return true
        }

        InterceptorStateRepository.diag("ACT", "system assist gesture failed")
        return false
    }

    /**
     * Same entry SystemUI uses for the assist gesture. Hidden @SystemApi, so called by reflection.
     * SearchManagerService.launchAssist does not require ACCESS_VOICE_INTERACTION_SERVICE — it
     * asks StatusBar to startAssist, which then shows the active VoiceInteractionSession.
     *
     * [args] is forwarded into the session. SystemUI adds SHOW_WITH_ASSIST | SHOW_WITH_SCREENSHOT.
     */
    fun invokeSystemAssistGesture(context: Context, args: Bundle? = null): Boolean {
        return try {
            val searchManager = context.getSystemService(Context.SEARCH_SERVICE) as? SearchManager
                ?: return false
            val launchAssist = pickLaunchAssistMethod(searchManager.javaClass.methods) ?: return false
            launchAssist.isAccessible = true
            val invokeArgs = Array(launchAssist.parameterCount) { index ->
                val type = launchAssist.parameterTypes[index]
                when (type) {
                    Bundle::class.java -> args
                    Int::class.javaPrimitiveType, Integer::class.java -> 0
                    else -> null
                }
            }
            launchAssist.invoke(searchManager, *invokeArgs)
            InterceptorStateRepository.diag(
                "ACT",
                "invoked SearchManager.launchAssist params=${launchAssist.parameterCount} " +
                    "keys=${args?.keySet()?.joinToString().orEmpty().ifEmpty { "null" }}",
            )
            true
        } catch (e: Exception) {
            val cause = e.cause ?: e
            InterceptorStateRepository.diag("ACT", "launchAssist failed: ${cause.message}")
            Log.e(TAG, "SearchManager.launchAssist failed", e)
            false
        }
    }

    fun hasLaunchAssist(context: Context): Boolean {
        val searchManager = context.getSystemService(Context.SEARCH_SERVICE) as? SearchManager
            ?: return false
        return pickLaunchAssistMethod(searchManager.javaClass.methods) != null
    }

    fun pickLaunchAssistMethod(methods: Array<java.lang.reflect.Method>): java.lang.reflect.Method? {
        val named = methods.filter { it.name == "launchAssist" }
        return named.firstOrNull { method ->
            method.parameterTypes.size == 1 && method.parameterTypes[0] == Bundle::class.java
        } ?: named.firstOrNull()
    }

    /**
     * Public ACTION_ASSIST with no package, matching a legacy activity-based assistant.
     * Skips the system disambiguation UI — that is not the assist gesture.
     */
    fun startUnscopedAssistIntent(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_ASSIST).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val resolved = intent.resolveActivity(context.packageManager)
            if (!resolvesToRealActivity(resolved?.packageName, resolved?.className)) {
                InterceptorStateRepository.diag(
                    "ACT",
                    "ACTION_ASSIST resolves to disambiguation ${resolved?.flattenToShortString()}, skip",
                )
                return false
            }
            context.startActivity(intent)
            InterceptorStateRepository.diag(
                "ACT",
                "launched ACTION_ASSIST pkg=${resolved?.packageName ?: "*"}",
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed ACTION_ASSIST", e)
            InterceptorStateRepository.diag("ACT", "ACTION_ASSIST failed: ${e.message}")
            false
        }
    }

    fun isAssistDisambiguation(packageName: String?, className: String?): Boolean {
        val pkg = packageName.orEmpty()
        val cls = className.orEmpty()
        return pkg.contains("intentresolver", ignoreCase = true) ||
            pkg.equals("com.huawei.android.internal.app", ignoreCase = true) ||
            cls.contains("ResolverActivity") ||
            cls.contains("ChooserActivity")
    }

    /** EMUI answers unresolved implicit intents with HwResolverActivity, not null. */
    fun resolvesToRealActivity(packageName: String?, className: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return !isAssistDisambiguation(packageName, className)
    }

    /** Shows the assistant picker. Never falls back to [launchDefaultAssistant], to avoid a cycle. */
    fun openAssistantChooser(context: Context): Boolean {
        return try {
            val intent = Intent(context, AssistantChooserActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open assistant chooser", e)
            InterceptorStateRepository.diag("ACT", "chooser failed: ${e.message}")
            Toast.makeText(context, "Pick an assistant in the app (Specific App)", Toast.LENGTH_LONG).show()
            false
        }
    }


    fun systemDefaultAssistantPackage(context: Context): String? =
        resolveSystemDefaultAssistant(context).first

    /**
     * True if [packageName] is a real assistant the user can launch.
     *
     * Activity-based assistants expose ACTION_ASSIST / ACTION_VOICE_COMMAND. Role holders like
     * HwCTS only register a VoiceInteractionService — that still counts. Skipping those as
     * "stub" opened the chooser even when Settings had them set as default.
     */
    fun hasAssistantSurface(context: Context, packageName: String): Boolean {
        return hasAssistActivity(context, packageName) ||
            hasVoiceInteractionService(context, packageName)
    }

    fun hasAssistActivity(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return listOf(Intent.ACTION_ASSIST, Intent.ACTION_VOICE_COMMAND).any { action ->
            try {
                Intent(action).setPackage(packageName).resolveActivity(pm) != null
            } catch (_: Exception) {
                false
            }
        }
    }

    fun hasVoiceInteractionService(context: Context, packageName: String): Boolean {
        val fromSecure = parseAssistantComponent(
            Settings.Secure.getString(context.contentResolver, "voice_interaction_service"),
        )
        if (fromSecure == packageName) return true
        return try {
            val intent = Intent("android.service.voice.VoiceInteractionService").setPackage(packageName)
            context.packageManager.queryIntentServices(intent, 0).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun describeAssistantSurface(
        hasAssistIntent: Boolean,
        hasVoiceCommand: Boolean,
        hasVoiceInteraction: Boolean,
    ): String {
        return when {
            hasAssistIntent -> "yes - via assist intent"
            hasVoiceCommand -> "yes - via voice command"
            hasVoiceInteraction -> "yes - VoiceInteractionService"
            else -> "no - will try launcher"
        }
    }

    /**
     * A copy-pasteable dump of every source that could name the system default assistant, plus
     * what each one actually holds. Exists because this can only be diagnosed on the affected
     * device, and OEM ROMs disagree about where the setting lives.
     */
    fun assistantResolutionReport(context: Context): List<Pair<String, String>> {
        val cr = context.contentResolver
        fun secure(key: String) = try {
            Settings.Secure.getString(cr, key)?.takeIf { it.isNotBlank() } ?: "(unset)"
        } catch (e: Exception) {
            "(error: ${e.message})"
        }

        val (winner, source) = resolveSystemDefaultAssistant(context)
        val assistAct = try {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_ASSIST),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "(none)"
        } catch (e: Exception) {
            "(error: ${e.message})"
        }
        val voiceAct = try {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_VOICE_COMMAND),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.let { "${it.packageName}/${it.name}" } ?: "(none)"
        } catch (e: Exception) {
            "(error: ${e.message})"
        }

        return listOf(
            "RESOLVED" to "${winner ?: "(none)"}  via $source",
            "RESOLVED exposes assistant UI" to
                (winner?.let { pkg ->
                    describeAssistantSurface(
                        hasAssistIntent = try {
                            Intent(Intent.ACTION_ASSIST).setPackage(pkg)
                                .resolveActivity(context.packageManager) != null
                        } catch (_: Exception) {
                            false
                        },
                        hasVoiceCommand = try {
                            Intent(Intent.ACTION_VOICE_COMMAND).setPackage(pkg)
                                .resolveActivity(context.packageManager) != null
                        } catch (_: Exception) {
                            false
                        },
                        hasVoiceInteraction = hasVoiceInteractionService(context, pkg),
                    )
                } ?: "n/a"),
            "secure.assistant" to secure("assistant"),
            "secure.voice_interaction_service" to secure("voice_interaction_service"),
            "secure.voice_recognition_service" to secure("voice_recognition_service"),
            "resolve ACTION_ASSIST" to assistAct,
            "resolve ACTION_VOICE_COMMAND" to voiceAct,
        )
    }

    fun resolveSystemDefaultAssistant(context: Context): Pair<String?, String> {
        val cr = context.contentResolver

        val fromAssistant = parseAssistantComponent(Settings.Secure.getString(cr, "assistant"))
        if (fromAssistant != null) return fromAssistant to "secure.assistant"

        val fromVis = parseAssistantComponent(Settings.Secure.getString(cr, "voice_interaction_service"))
        if (fromVis != null) return fromVis to "secure.voice_interaction_service"

        // RoleManager.getRoleHolders() is a system API (needs MANAGE_ROLE_HOLDERS), so a
        // normal app cannot ask who holds ROLE_ASSISTANT. The Settings.Secure keys above are the
        // only readable source; everything after this is a guess.

        // Last resort. On a device with Google services this usually answers Google whether or
        // not the user picked it, so it is reported distinctly rather than trusted silently.
        val resolvedInfo = try {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_ASSIST),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        } catch (_: Exception) {
            null
        }
        val resolvedPkg = resolvedInfo?.activityInfo?.packageName
        val resolvedCls = resolvedInfo?.activityInfo?.name
        if (!resolvesToRealActivity(resolvedPkg, resolvedCls)) {
            return null to "resolveActivity(ACTION_ASSIST)"
        }
        return resolvedPkg to "resolveActivity(ACTION_ASSIST)"
    }

    fun parseAssistantComponent(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw.substringBefore('/').takeIf { it.isNotEmpty() }
    }

    private fun isPackageInstalled(pm: PackageManager, packageName: String): Boolean {
        return try {
            pm.getLaunchIntentForPackage(packageName) != null ||
                pm.getApplicationInfo(packageName, 0).enabled
        } catch (_: Exception) {
            false
        }
    }

    fun toggleFlashlight(context: Context): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            if (cameraManager != null) {
                ensureTorchCallback(cameraManager)
                val cameraId = findFlashCameraId(cameraManager)
                if (cameraId != null) {
                    torchCameraId = cameraId
                    val turnOn = !isTorchOn
                    cameraManager.setTorchMode(cameraId, turnOn)
                    isTorchOn = turnOn
                    val statusText = if (turnOn) "Flashlight Turned ON" else "Flashlight Turned OFF"
                    Toast.makeText(context, statusText, Toast.LENGTH_SHORT).show()
                    true
                } else {
                    Toast.makeText(context, "No camera with flashlight found", Toast.LENGTH_SHORT).show()
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle flashlight", e)
            Toast.makeText(context, "Flashlight error: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    private fun findFlashCameraId(cameraManager: CameraManager): String? {
        return cameraManager.cameraIdList.firstOrNull { id ->
            try {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) ?: false
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                hasFlash && facing == CameraCharacteristics.LENS_FACING_BACK
            } catch (_: Exception) {
                false
            }
        } ?: cameraManager.cameraIdList.firstOrNull()
    }

    private fun ensureTorchCallback(cameraManager: CameraManager) {
        if (torchCallbackRegistered) return
        try {
            cameraManager.registerTorchCallback(
                object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                        if (torchCameraId == null || cameraId == torchCameraId) {
                            isTorchOn = enabled
                            torchCameraId = cameraId
                        }
                    }
                },
                null,
            )
            torchCallbackRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register torch callback", e)
        }
    }

    fun toggleMute(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager != null) {
                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (currentVol > 0) {
                    lastMusicVolume = currentVol
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
                    Toast.makeText(context, "Muted Media Volume", Toast.LENGTH_SHORT).show()
                } else {
                    val restoreVol = if (lastMusicVolume > 0) lastMusicVolume else (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 2)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, AudioManager.FLAG_SHOW_UI)
                    Toast.makeText(context, "Unmuted Media Volume", Toast.LENGTH_SHORT).show()
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to toggle mute", e)
            Toast.makeText(context, "Mute toggle error: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
