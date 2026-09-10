package viva.la.circle.remap

/**
 * Identity of Intercepted assistants this app Remaps (BlueLM, Celia, …).
 *
 * Matching unions every known vendor surface: recognising a Vivo package on a Huawei phone
 * costs nothing and keeps matching independent of getprop / VendorProfile.current().
 */
object InterceptedAssistant {

    private data class VendorSurface(
        val packages: Set<String>,
        val packageHints: List<String>,
        val secondaryUiClassHints: List<String>,
    )

    private val VIVO = VendorSurface(
        packages = setOf(
            "com.vivo.agent",
            "com.vivo.vpa",
            "com.bbk.voiceassistant",
            "com.vivo.ai.copilot",
            "com.vivo.blue.assistant",
            "com.vivo.bluelm",
        ),
        packageHints = listOf(
            "bluelm",
            "jovi",
            "vivoassistant",
            "bbk.voiceassistant",
        ),
        secondaryUiClassHints = listOf(
            ".settings.",
            "circletosearch",
            ".photos.ui",
            "PrivacyPolicy",
            "UserPolicy",
            "AboutActivity",
            "FeedBackDialog",
        ),
    )

    private val HUAWEI = VendorSurface(
        packages = setOf(
            "com.huawei.hiassistantoversea",
            "com.huawei.hiassistant",
            "com.huawei.vassistant",
        ),
        packageHints = listOf(
            "hiassistant",
            "vassistant",
        ),
        secondaryUiClassHints = emptyList(),
    )

    private val ALL = listOf(VIVO, HUAWEI)

    /** Exact packages from every vendor surface. Kept for diagnostics and tests. */
    val knownPackages: Set<String>
        get() = ALL.flatMap { it.packages }.toSet()

    /**
     * True when [packageName] is an Intercepted assistant (Wake UI or Secondary UI).
     * [className] is unused for matching today; kept so callers share one interface.
     */
    @Suppress("UNUSED_PARAMETER")
    fun isInterceptedAssistant(
        packageName: String?,
        className: String?,
        ownPackageName: String = "",
    ): Boolean {
        val pkg = packageName?.lowercase() ?: return false
        if (pkg.isEmpty()) return false

        if (ownPackageName.isNotEmpty() &&
            (pkg == ownPackageName.lowercase() || pkg.startsWith("viva.la.circle"))
        ) {
            return false
        }

        if (knownPackages.contains(pkg)) return true

        // Vivo compound rule: both tokens required (bare "agent" must not match).
        if (pkg.contains("vivo") && pkg.contains("agent")) return true

        return ALL.any { surface ->
            surface.packageHints.any { pkg.contains(it) }
        }
    }

    /** Secondary UI must Pass-through (settings, gallery, in-assistant search). */
    fun isSecondaryUi(className: String?): Boolean {
        val cls = className.orEmpty()
        if (cls.isEmpty()) return false
        return ALL.any { surface ->
            surface.secondaryUiClassHints.any { cls.contains(it) }
        }
    }

    /** Wake UI is an Intercepted assistant surface that should Remap. */
    fun isWakeUi(
        packageName: String?,
        className: String?,
        ownPackageName: String = "",
    ): Boolean {
        if (!isInterceptedAssistant(packageName, className, ownPackageName)) return false
        return !isSecondaryUi(className)
    }

    /** True when launching the OS default assistant would re-open an Intercepted assistant. */
    fun wouldLoopToInterceptedAssistant(
        systemDefaultPackage: String?,
        ownPackageName: String = "",
    ): Boolean = isInterceptedAssistant(systemDefaultPackage, null, ownPackageName)
}
