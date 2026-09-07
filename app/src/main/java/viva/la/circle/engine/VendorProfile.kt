package viva.la.circle.engine

/**
 * Per-OEM assistant description.
 *
 * Detection picks at most one profile for *tuning* (dismiss budget, delays, UI label), but
 * assistant matching deliberately unions every profile: recognising a Vivo package on a Huawei
 * phone costs nothing (it will not be installed) and it keeps matching independent of a getprop
 * probe that cannot run in unit tests.
 */
data class VendorProfile(
    val id: String,
    val label: String,
    /** Exact packages whose window means "the OEM assistant just opened". */
    val assistantPackages: Set<String>,
    /** Lowercase substrings, matched against the package name only — never the class name. */
    val assistantPackageHints: List<String>,
    /** Class-name substrings that are the assistant's *other* screens and must not be remapped. */
    val secondaryUiClassHints: List<String>,
    val maxDismissBacks: Int,
    val dismissTimeoutMs: Long,
    val defaultDismissDelayMs: Int,
    /** How often the dismiss loop re-checks the top window after a BACK. */
    val pollMs: Long,
    /** Minimum gap between GLOBAL_ACTION_BACK presses while dismissing. */
    val hammerMinIntervalMs: Long,
    /** Cap on waiting for the assistant window to become top before the first BACK. */
    val focusWaitTimeoutMs: Long,
    /** Poll while waiting for the assistant to become the top window. */
    val focusPollMs: Long,
) {
    companion object {
        val VIVO = VendorProfile(
            id = "vivo",
            label = "OriginOS / Funtouch",
            assistantPackages = setOf(
                "com.vivo.agent",
                "com.vivo.vpa",
                "com.bbk.voiceassistant",
                "com.vivo.ai.copilot",
                "com.vivo.blue.assistant",
                "com.vivo.bluelm",
            ),
            assistantPackageHints = listOf(
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
            maxDismissBacks = 3,
            dismissTimeoutMs = 400L,
            defaultDismissDelayMs = 100,
            pollMs = 30L,
            hammerMinIntervalMs = 40L,
            focusWaitTimeoutMs = 250L,
            focusPollMs = 16L,
        )

        /**
         * Huawei Celia. Single overlay; one BACK is the measured starting point (see
         * PLAN_HUAWEI_V2.md). Poll/interval are slower than Vivo so a closing animation is not
         * mistaken for "still present".
         */
        val HUAWEI = VendorProfile(
            id = "huawei",
            label = "EMUI / HarmonyOS",
            assistantPackages = setOf(
                "com.huawei.hiassistantoversea",
                "com.huawei.hiassistant",
                "com.huawei.vassistant",
            ),
            assistantPackageHints = listOf(
                "hiassistant",
                "vassistant",
            ),
            secondaryUiClassHints = emptyList(),
            maxDismissBacks = 1,
            dismissTimeoutMs = 400L,
            defaultDismissDelayMs = 100,
            pollMs = 120L,
            hammerMinIntervalMs = 120L,
            focusWaitTimeoutMs = 250L,
            focusPollMs = 16L,
        )

        val GENERIC = VendorProfile(
            id = "generic",
            label = "unknown",
            assistantPackages = emptySet(),
            assistantPackageHints = emptyList(),
            secondaryUiClassHints = emptyList(),
            maxDismissBacks = 3,
            dismissTimeoutMs = 400L,
            defaultDismissDelayMs = 100,
            pollMs = 30L,
            hammerMinIntervalMs = 40L,
            focusWaitTimeoutMs = 250L,
            focusPollMs = 16L,
        )

        /** Every profile consulted when matching an assistant window. */
        val ALL = listOf(VIVO, HUAWEI)

        fun byId(id: String?): VendorProfile = ALL.firstOrNull { it.id == id } ?: GENERIC

        /** Chooses a profile from build props. Pure, so it can be unit tested. */
        fun forProps(
            manufacturer: String?,
            vivoDisplayId: String?,
            emuiVersion: String?,
            harmonyVersion: String?,
            brand: String? = null,
            hwPlatformVersion: String? = null,
        ): VendorProfile {
            val vendor = listOfNotNull(manufacturer, brand).joinToString(" ").lowercase()
            return when {
                vendor.contains("huawei") || vendor.contains("honor") -> HUAWEI
                !emuiVersion.isNullOrBlank() ||
                    !harmonyVersion.isNullOrBlank() ||
                    !hwPlatformVersion.isNullOrBlank() -> HUAWEI
                vendor.contains("vivo") || !vivoDisplayId.isNullOrBlank() -> VIVO
                else -> GENERIC
            }
        }

        /**
         * Human-readable OS line for diagnostics. Huawei must not fall through to OriginOs's
         * `unknown` when only `ro.vivo.os.*` is empty.
         */
        fun osLabel(
            profile: VendorProfile,
            vivoLabel: String,
            emuiVersion: String?,
            harmonyVersion: String?,
        ): String {
            return when (profile.id) {
                HUAWEI.id -> {
                    val ver = emuiVersion?.takeIf { it.isNotBlank() }
                        ?: harmonyVersion?.takeIf { it.isNotBlank() }
                    if (ver != null) "${profile.label} ($ver)" else profile.label
                }
                VIVO.id -> vivoLabel.takeIf { it.isNotBlank() && it != "unknown" } ?: profile.label
                else -> vivoLabel.takeIf { it.isNotBlank() } ?: profile.label
            }
        }

        @Volatile
        private var cached: VendorProfile? = null

        fun current(): VendorProfile {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val detected = forProps(
                    manufacturer = OriginOs.readProp("ro.product.manufacturer"),
                    brand = OriginOs.readProp("ro.product.brand"),
                    vivoDisplayId = OriginOs.readProp("ro.vivo.os.build.display.id"),
                    emuiVersion = OriginOs.readProp("ro.build.version.emui"),
                    harmonyVersion = OriginOs.readProp("ro.build.version.harmonyos"),
                    hwPlatformVersion = OriginOs.readProp("hw_sc.build.platform.version"),
                )
                cached = detected
                return detected
            }
        }

        fun resetCacheForTests() {
            cached = null
        }

        /** True if [packageName] is an OEM assistant we intercept, per any known profile. */
        fun matchesAnyAssistant(packageName: String): Boolean {
            val pkg = packageName.lowercase()
            return ALL.any { profile ->
                profile.assistantPackages.contains(pkg) ||
                    profile.assistantPackageHints.any { pkg.contains(it) }
            }
        }

        /** True if [className] is one of the assistant's secondary screens, per any profile. */
        fun matchesAnySecondaryUi(className: String): Boolean =
            ALL.any { profile -> profile.secondaryUiClassHints.any { className.contains(it) } }
    }
}
