package viva.la.circle

import viva.la.circle.ui.formatTimestamp
import viva.la.circle.ui.parseKeyCodeFromDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MainScreenTest {

    @Test
    fun testFormatTimestampNever() {
        assertEquals("Never", formatTimestamp(0L))
        assertEquals("Never", formatTimestamp(-1000L))
    }

    @Test
    fun testFormatTimestampValid() {
        val timestamp = 1700000000000L // Nov 14, 2023 ...
        val formatted = formatTimestamp(timestamp)
        assertNotEquals("Never", formatted)
        assertEquals(true, formatted.contains("2023"))
    }

    @Test
    fun testParseKeyCodeFromDetail() {
        assertEquals(27, parseKeyCodeFromDetail("27 KEYCODE_CAMERA action=DOWN scan=766"))
        assertEquals(80, parseKeyCodeFromDetail("80 KEYCODE_FOCUS action=UP"))
        assertNull(parseKeyCodeFromDetail("rejected: cameraAction=NONE"))
    }
}
