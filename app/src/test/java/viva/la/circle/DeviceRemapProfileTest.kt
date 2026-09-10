package viva.la.circle

import org.junit.Assert.assertEquals
import org.junit.Test
import viva.la.circle.engine.VendorProfile
import viva.la.circle.remap.DeviceRemapProfile

class DeviceRemapProfileTest {

    @Test
    fun timingComesFromVendorProfile() {
        val timing = DeviceRemapProfile.timing(VendorProfile.HUAWEI)
        assertEquals(1, timing.maxDismissBacks)
        assertEquals(120L, timing.pollMs)
        assertEquals(120L, timing.hammerMinIntervalMs)
    }

    @Test
    fun forPropsMatchesVendorDetection() {
        assertEquals(
            VendorProfile.HUAWEI,
            DeviceRemapProfile.forProps("HUAWEI", null, "EmotionUI_14.0.0", null),
        )
        assertEquals(
            VendorProfile.VIVO,
            DeviceRemapProfile.forProps("vivo", "OriginOS 6", null, null),
        )
    }
}
