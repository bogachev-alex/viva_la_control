package viva.la.circle

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import viva.la.circle.model.TargetAction
import viva.la.circle.remap.FireTargetAction
import viva.la.circle.remap.RemapBack
import viva.la.circle.remap.RemapClock
import viva.la.circle.remap.RemapDiag
import viva.la.circle.remap.RemapSession
import viva.la.circle.remap.RemapTiming
import viva.la.circle.remap.RemapWindows
import viva.la.circle.remap.WindowSnap

class RemapSessionTest {

    private val own = "viva.la.circle"
    private val celia = "com.huawei.hiassistantoversea"
    private val browser = "com.android.chrome"

    private class FakeClock(startMs: Long = 0L) : RemapClock {
        var now = startMs
        val delays = mutableListOf<Long>()
        override fun nowMs(): Long = now
        override suspend fun delayMs(ms: Long) {
            delays += ms
            now += ms
        }
    }

    @Test
    fun dismissesWhenAssistantIsTopThenFires() = runBlocking {
        val snaps = ArrayDeque(
            listOf(
                WindowSnap(celia, listOf(celia, browser)),
                WindowSnap(browser, listOf(browser)),
            ),
        )
        val backs = mutableListOf<String>()
        val fires = mutableListOf<TargetAction>()
        val logs = mutableListOf<String>()
        val clock = FakeClock()

        val session = RemapSession(
            windows = RemapWindows {
                if (snaps.isEmpty()) WindowSnap(browser, listOf(browser)) else snaps.removeFirst()
            },
            back = RemapBack {
                backs += "back"
                true
            },
            clock = clock,
            fire = FireTargetAction { action, _ ->
                fires += action
                true
            },
            diag = RemapDiag { kind, detail -> logs += "$kind:$detail" },
            ownPackage = own,
        )

        val ok = session.run(
            action = TargetAction.FLASHLIGHT,
            specificPackage = null,
            preAssistPackage = browser,
            timing = RemapTiming(
                maxDismissBacks = 3,
                dismissTimeoutMs = 400,
                pollMs = 30,
                hammerMinIntervalMs = 40,
                focusWaitTimeoutMs = 250,
                focusPollMs = 16,
            ),
        )

        assertTrue(ok)
        assertTrue(backs.isNotEmpty())
        assertEquals(listOf(TargetAction.FLASHLIGHT), fires)
        assertTrue(logs.any { it.startsWith("BLM:") })
    }

    @Test
    fun hwctsUsesSingleBackAndSkipsWaitUntilGone() = runBlocking {
        val snaps = ArrayDeque(listOf(WindowSnap(celia, listOf(celia))))
        var backCount = 0
        val clock = FakeClock()
        val session = RemapSession(
            windows = RemapWindows {
                if (snaps.isEmpty()) WindowSnap(celia, listOf(celia)) else snaps.removeFirst()
            },
            back = RemapBack {
                backCount++
                true
            },
            clock = clock,
            fire = FireTargetAction { _, _ -> true },
            diag = RemapDiag { _, _ -> },
            ownPackage = own,
        )

        session.run(
            action = TargetAction.HWCTS,
            specificPackage = null,
            preAssistPackage = browser,
            timing = RemapTiming(
                maxDismissBacks = 3,
                dismissTimeoutMs = 400,
                pollMs = 30,
                hammerMinIntervalMs = 40,
                focusWaitTimeoutMs = 250,
                focusPollMs = 16,
            ),
        )

        assertEquals(1, RemapSession.dismissBackBudget(TargetAction.HWCTS, 3))
        assertFalse(RemapSession.shouldWaitUntilGone(TargetAction.HWCTS))
        assertEquals(1, backCount)
        assertTrue(clock.delays.none { it == 250L }) // no CtS settle for HWCTS wait path skipped
    }

    @Test
    fun overshootStopsFurtherBacks() {
        assertTrue(
            RemapSession.isDismissOvershoot(
                topPackage = "com.huawei.android.launcher",
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
        assertFalse(
            RemapSession.isDismissOvershoot(
                topPackage = browser,
                preAssistPackage = browser,
                ownPackage = own,
            ),
        )
        assertTrue(RemapSession.shouldPressDismissBack(celia, own))
        assertFalse(RemapSession.shouldPressDismissBack(browser, own))
        assertTrue(
            RemapSession.canDismissBack(
                actionLaunched = false,
                backPressCount = 0,
                assistantPresent = true,
                elapsedSinceLastBackMs = 100,
                maxBacks = 3,
                minIntervalMs = 40,
            ),
        )
        assertFalse(
            RemapSession.canDismissBack(
                actionLaunched = false,
                backPressCount = 3,
                assistantPresent = true,
                elapsedSinceLastBackMs = 100,
                maxBacks = 3,
                minIntervalMs = 40,
            ),
        )
    }

    @Test
    fun circleToSearchSettlesAfterDismiss() = runBlocking {
        val snaps = ArrayDeque(
            listOf(
                WindowSnap(celia, listOf(celia)),
                WindowSnap(browser, listOf(browser)),
            ),
        )
        val clock = FakeClock()
        val session = RemapSession(
            windows = RemapWindows {
                if (snaps.isEmpty()) WindowSnap(browser, listOf(browser)) else snaps.removeFirst()
            },
            back = RemapBack { true },
            clock = clock,
            fire = FireTargetAction { _, _ -> true },
            diag = RemapDiag { _, _ -> },
            ownPackage = own,
        )
        session.run(
            action = TargetAction.CIRCLE_TO_SEARCH,
            specificPackage = null,
            preAssistPackage = browser,
            timing = RemapTiming(
                maxDismissBacks = 3,
                dismissTimeoutMs = 60,
                pollMs = 30,
                hammerMinIntervalMs = 40,
                focusWaitTimeoutMs = 250,
                focusPollMs = 16,
            ),
        )
        assertTrue(clock.delays.contains(250L))
    }
}
