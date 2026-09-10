package viva.la.circle.remap

import viva.la.circle.engine.OriginOs
import viva.la.circle.engine.VendorProfile

/**
 * Device Remap profile: timings + detection for Remap session.
 * Matching lives on [InterceptedAssistant]; this module owns who/how-long tuning.
 */
object DeviceRemapProfile {

    fun current(): VendorProfile = VendorProfile.current()

    fun forProps(
        manufacturer: String?,
        vivoDisplayId: String?,
        emuiVersion: String?,
        harmonyVersion: String?,
        brand: String? = null,
        hwPlatformVersion: String? = null,
    ): VendorProfile = VendorProfile.forProps(
        manufacturer = manufacturer,
        vivoDisplayId = vivoDisplayId,
        emuiVersion = emuiVersion,
        harmonyVersion = harmonyVersion,
        brand = brand,
        hwPlatformVersion = hwPlatformVersion,
    )

    fun timing(profile: VendorProfile = current()): RemapTiming = RemapTiming.fromVendor(
        maxDismissBacks = profile.maxDismissBacks,
        dismissTimeoutMs = profile.dismissTimeoutMs,
        pollMs = profile.pollMs,
        hammerMinIntervalMs = profile.hammerMinIntervalMs,
        focusWaitTimeoutMs = profile.focusWaitTimeoutMs,
        focusPollMs = profile.focusPollMs,
    )

    fun osLabel(
        profile: VendorProfile = current(),
        vivoLabel: String = OriginOs.detect().label,
        emuiVersion: String? = OriginOs.readProp("ro.build.version.emui"),
        harmonyVersion: String? = OriginOs.readProp("ro.build.version.harmonyos"),
    ): String = VendorProfile.osLabel(profile, vivoLabel, emuiVersion, harmonyVersion)

    fun resetCacheForTests() = VendorProfile.resetCacheForTests()
}
