package viva.la.circle

import viva.la.circle.model.BlueLMActionConfig
import viva.la.circle.model.TargetAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueLMActionConfigTest {

    @Test
    fun decodeStored_missingOrBlank_isUnset() {
        assertNull(BlueLMActionConfig.decodeStored(null))
        assertNull(BlueLMActionConfig.decodeStored(""))
        assertNull(BlueLMActionConfig.decodeStored("   "))
    }

    @Test
    fun decodeStored_keepsExplicitActionsIncludingDefaultAssistant() {
        assertEquals(TargetAction.DEFAULT_ASSISTANT, BlueLMActionConfig.decodeStored("DEFAULT_ASSISTANT"))
        assertEquals(TargetAction.NONE, BlueLMActionConfig.decodeStored("NONE"))
        assertEquals(TargetAction.FLASHLIGHT, BlueLMActionConfig.decodeStored("FLASHLIGHT"))
        assertEquals(TargetAction.CIRCLE_TO_SEARCH, BlueLMActionConfig.decodeStored("CIRCLE_TO_SEARCH"))
        assertNull(BlueLMActionConfig.decodeStored("NOT_A_REAL_ACTION"))
    }

    @Test
    fun configuredAndRemapGates() {
        assertFalse(BlueLMActionConfig.isConfigured(null))
        assertTrue(BlueLMActionConfig.isConfigured(TargetAction.NONE))
        assertTrue(BlueLMActionConfig.isConfigured(TargetAction.DEFAULT_ASSISTANT))
        assertTrue(BlueLMActionConfig.isConfigured(TargetAction.FLASHLIGHT))

        assertFalse(BlueLMActionConfig.shouldRemap(null))
        assertFalse(BlueLMActionConfig.shouldRemap(TargetAction.NONE))
        assertTrue(BlueLMActionConfig.shouldRemap(TargetAction.FLASHLIGHT))
        assertTrue(BlueLMActionConfig.shouldRemap(TargetAction.DEFAULT_ASSISTANT))
    }
}
