package viva.la.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.model.TargetAction
import viva.la.circle.model.VolumeShortAction
import viva.la.circle.remap.VolumeKeyPolicy

class VolumeKeyPolicyTest {

    @Test
    fun armNeverInCall() {
        assertFalse(
            VolumeKeyPolicy.shouldArmKey(
                skipTracksEnabled = true,
                shortRemapEnabled = true,
                shortAction = VolumeShortAction.Remap(TargetAction.FLASHLIGHT),
                mediaPlaying = true,
                inCall = true,
            ),
        )
    }

    @Test
    fun armForSkipWhenMediaPlaying() {
        assertTrue(
            VolumeKeyPolicy.shouldArmKey(
                skipTracksEnabled = true,
                shortRemapEnabled = false,
                shortAction = VolumeShortAction.Volume,
                mediaPlaying = true,
                inCall = false,
            ),
        )
    }

    @Test
    fun noArmWhenSkipOnButNoMediaAndShortIsVolume() {
        assertFalse(
            VolumeKeyPolicy.shouldArmKey(
                skipTracksEnabled = true,
                shortRemapEnabled = false,
                shortAction = VolumeShortAction.Volume,
                mediaPlaying = false,
                inCall = false,
            ),
        )
    }

    @Test
    fun armPerKeyOnlyWhenShortRemapIsAction() {
        assertTrue(
            VolumeKeyPolicy.shouldArmKey(
                skipTracksEnabled = false,
                shortRemapEnabled = true,
                shortAction = VolumeShortAction.Remap(TargetAction.MUTE_TOGGLE),
                mediaPlaying = false,
                inCall = false,
            ),
        )
        assertFalse(
            VolumeKeyPolicy.shouldArmKey(
                skipTracksEnabled = false,
                shortRemapEnabled = true,
                shortAction = VolumeShortAction.Volume,
                mediaPlaying = false,
                inCall = false,
            ),
        )
    }

    @Test
    fun downFirstNativeVolumeTapArmsDouble() {
        assertEquals(
            VolumeKeyPolicy.DownDecision.PassThroughArmDouble,
            VolumeKeyPolicy.onDown(
                armed = true,
                canSkip = true,
                passThroughVolume = true,
                repeatCount = 0,
                alreadyConsuming = false,
                secondTapWithinWindow = false,
            ),
        )
    }

    @Test
    fun downSecondNativeVolumeTapFiresSkip() {
        assertEquals(
            VolumeKeyPolicy.DownDecision.ConsumeFireSkip,
            VolumeKeyPolicy.onDown(
                armed = true,
                canSkip = true,
                passThroughVolume = true,
                repeatCount = 0,
                alreadyConsuming = false,
                secondTapWithinWindow = true,
            ),
        )
    }

    @Test
    fun downNativeVolumeHoldPassesThrough() {
        assertEquals(
            VolumeKeyPolicy.DownDecision.PassThrough,
            VolumeKeyPolicy.onDown(
                armed = true,
                canSkip = true,
                passThroughVolume = true,
                repeatCount = 3,
                alreadyConsuming = false,
                secondTapWithinWindow = false,
            ),
        )
    }

    @Test
    fun downNativeVolumeNoSkipWhenNoMedia() {
        assertEquals(
            VolumeKeyPolicy.DownDecision.PassThrough,
            VolumeKeyPolicy.onDown(
                armed = true,
                canSkip = false,
                passThroughVolume = true,
                repeatCount = 0,
                alreadyConsuming = false,
                secondTapWithinWindow = true,
            ),
        )
    }

    @Test
    fun downConsumesSkipJobWhenShortRemap() {
        assertEquals(
            VolumeKeyPolicy.DownDecision.ConsumeStartSkipJob,
            VolumeKeyPolicy.onDown(
                armed = true,
                canSkip = true,
                passThroughVolume = false,
                repeatCount = 0,
                alreadyConsuming = false,
                secondTapWithinWindow = false,
            ),
        )
    }

    @Test
    fun downShortOnlyWhenRemapArmedWithoutSkip() {
        assertEquals(
            VolumeKeyPolicy.DownDecision.ConsumeShortOnly,
            VolumeKeyPolicy.onDown(
                armed = true,
                canSkip = false,
                passThroughVolume = false,
                repeatCount = 0,
                alreadyConsuming = false,
                secondTapWithinWindow = false,
            ),
        )
    }

    @Test
    fun downContinuesConsumingRemapRepeats() {
        assertEquals(
            VolumeKeyPolicy.DownDecision.ContinueConsuming,
            VolumeKeyPolicy.onDown(
                armed = true,
                canSkip = true,
                passThroughVolume = false,
                repeatCount = 2,
                alreadyConsuming = true,
                secondTapWithinWindow = false,
            ),
        )
    }

    @Test
    fun upAfterSkipIgnoresShortAction() {
        assertEquals(
            VolumeKeyPolicy.UpDecision.AfterSkip,
            VolumeKeyPolicy.onUp(
                wasConsuming = true,
                skipFired = true,
                shortAction = VolumeShortAction.Remap(TargetAction.FLASHLIGHT),
            ),
        )
    }

    @Test
    fun upFiresShortRemap() {
        assertEquals(
            VolumeKeyPolicy.UpDecision.FireAction(TargetAction.SCREENSHOT, null),
            VolumeKeyPolicy.onUp(
                wasConsuming = true,
                skipFired = false,
                shortAction = VolumeShortAction.Remap(TargetAction.SCREENSHOT),
            ),
        )
    }

    @Test
    fun normalizeTimeoutSnapsToPreset() {
        assertEquals(500L, VolumeKeyPolicy.normalizeTimeoutMs(480L))
        assertEquals(300L, VolumeKeyPolicy.normalizeTimeoutMs(310L))
        assertEquals(700L, VolumeKeyPolicy.normalizeTimeoutMs(900L))
    }

    @Test
    fun volumeShortActionRoundTrip() {
        assertEquals(VolumeShortAction.Volume, VolumeShortAction.fromStored(null, null))
        assertEquals(VolumeShortAction.Volume, VolumeShortAction.fromStored("VOLUME", null))
        assertEquals(VolumeShortAction.Volume, VolumeShortAction.fromStored("CIRCLE_TO_SEARCH", null))
        val remap = VolumeShortAction.fromStored("SPECIFIC_APP", "com.spotify.music")
        assertEquals(
            VolumeShortAction.Remap(TargetAction.SPECIFIC_APP, "com.spotify.music"),
            remap,
        )
        assertEquals("SPECIFIC_APP", VolumeShortAction.toStoredName(remap))
        assertEquals("com.spotify.music", VolumeShortAction.toStoredPackage(remap))
    }
}
