package viva.la.circle.engine

/**
 * Per-OEM Remap *tuning* (dismiss budget, delays, UI label).
 *
 * Intercepted assistant package matching lives in [viva.la.circle.remap.InterceptedAssistant].
 * Detection picks at most one profile for timing; identity matching unions every vendor surface
 * and does not depend on [current].
 */
data class VendorProfile(
    val id: String,
    val label: String,
    val maxDismissBacks: Int,
    val dismissTimeoutMs: Long,
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
            maxDismissBacks = 3,
            dismissTimeoutMs = 400L,
            pollMs = 30L,
            hammerMinIntervalMs = 40L,
            focusWaitTimeoutMs = 250L,
            focusPollMs = 16L,
        )

        /**
         * Huawei Celia. Single overlay; one BACK is the measured starting point
         * (measured on-device). Poll/interval are slower than Vivo so a closing animation is not
         * mistaken for "still present".
         */
        val HUAWEI = VendorProfile(
            id = "huawei",
            label = "EMUI / HarmonyOS",
            maxDismissBacks = 1,
            dismissTimeoutMs = 400L,
            pollMs = 120L,
            hammerMinIntervalMs = 120L,
            focusWaitTimeoutMs = 250L,
            focusPollMs = 16L,
        )

        val GENERIC = VendorProfile(
            id = "generic",
            label = "unknown",
            maxDismissBacks = 3,
            dismissTimeoutMs = 400L,
            pollMs = 30L,
            hammerMinIntervalMs = 40L,
            focusWaitTimeoutMs = 250L,
            focusPollMs = 16L,
        )

        /** Every profile consulted when choosing device tuning. */
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
    }
}
