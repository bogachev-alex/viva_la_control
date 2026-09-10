package viva.la.circle.remap

import viva.la.circle.engine.CircleToSearch
import viva.la.circle.model.TargetAction

/**
 * One BlueLM wake: dismiss Intercepted assistant Wake UI, then fire TargetAction.
 */
class RemapSession(
    private val windows: RemapWindows,
    private val back: RemapBack,
    private val clock: RemapClock,
    private val fire: FireTargetAction,
    private val diag: RemapDiag,
    private val ownPackage: String,
) {
    private var actionLaunched = false
    private var backPressCount = 0
    private var lastBackMs = 0L

    suspend fun run(
        action: TargetAction,
        specificPackage: String?,
        preAssistPackage: String?,
        timing: RemapTiming,
    ): Boolean {
        actionLaunched = false
        backPressCount = 0
        lastBackMs = 0L

        val backBudget = dismissBackBudget(action, timing.maxDismissBacks)
        val waitUntilGone = shouldWaitUntilGone(action)

        var assistantBecameTop = false
        val focusDeadline = clock.nowMs() + timing.focusWaitTimeoutMs
        while (clock.nowMs() <= focusDeadline) {
            val snap = windows.snapshot()
            diag.log(
                "BLM",
                "focus windows=[${snap.packages.joinToString()}] top=${snap.topPackage ?: "-"}",
            )
            if (shouldPressDismissBack(snap.topPackage, ownPackage)) {
                assistantBecameTop = true
                if (backBudget > 0) {
                    pressDismissBack(
                        reason = "initial",
                        maxBacks = backBudget,
                        minIntervalMs = timing.hammerMinIntervalMs,
                        assumePresent = true,
                        knownTop = snap.topPackage,
                        knownPackages = snap.packages,
                    )
                }
                break
            }
            if (clock.nowMs() + timing.focusPollMs > focusDeadline) break
            clock.delayMs(timing.focusPollMs)
        }

        if (!assistantBecameTop) {
            diag.log("BLM", "assistant never top within ${timing.focusWaitTimeoutMs}ms, skip BACK")
        } else if (waitUntilGone) {
            val deadline = clock.nowMs() + timing.dismissTimeoutMs
            while (clock.nowMs() < deadline) {
                clock.delayMs(timing.pollMs)
                val snap = windows.snapshot()
                diag.log(
                    "BLM",
                    "poll windows=[${snap.packages.joinToString()}] top=${snap.topPackage ?: "-"}",
                )
                if (isDismissOvershoot(snap.topPackage, preAssistPackage, ownPackage)) {
                    diag.log(
                        "BLM",
                        "stop BACK: overshoot top=${snap.topPackage} preAssist=${preAssistPackage ?: "-"}",
                    )
                    break
                }
                if (!shouldPressDismissBack(snap.topPackage, ownPackage)) {
                    diag.log(
                        "BLM",
                        "stop BACK: assistant gone top=${snap.topPackage ?: "-"}",
                    )
                    break
                }
                pressDismissBack(
                    reason = "poll",
                    maxBacks = backBudget,
                    minIntervalMs = timing.hammerMinIntervalMs,
                    assumePresent = true,
                    knownTop = snap.topPackage,
                    knownPackages = snap.packages,
                )
            }
            val settleMs = CircleToSearch.extraSettleMs(action).toLong()
            if (settleMs > 0) clock.delayMs(settleMs)
        }

        val endSnap = windows.snapshot()
        if (isAssistantTopWindow(endSnap.topPackage, ownPackage)) {
            diag.log(
                "BLM",
                "assistant still top after $backPressCount backs top=${endSnap.topPackage}",
            )
        }
        diag.log("CTS", "top=${endSnap.topPackage} backs=$backPressCount")
        val fired = fire.fire(action, specificPackage)
        diag.log("BLM", "launched $action result=$fired")
        if (fired) actionLaunched = true
        return fired
    }

    private fun pressDismissBack(
        reason: String,
        maxBacks: Int,
        minIntervalMs: Long,
        assumePresent: Boolean,
        knownTop: String?,
        knownPackages: List<String?>,
    ): Boolean {
        val now = clock.nowMs()
        val present = assumePresent || isAssistantTopWindow(knownTop, ownPackage)
        if (!canDismissBack(
                actionLaunched = actionLaunched,
                backPressCount = backPressCount,
                assistantPresent = present,
                elapsedSinceLastBackMs = now - lastBackMs,
                maxBacks = maxBacks,
                minIntervalMs = minIntervalMs,
                assumePresent = assumePresent,
            )
        ) {
            if (!present && !assumePresent) {
                diag.log("BLM", "skip BACK ($reason): assistant not top top=${knownTop ?: "-"}")
            }
            return false
        }
        lastBackMs = now
        backPressCount++
        val backOk = back.pressBack()
        diag.log(
            "BLM",
            "BACK=$backOk reason=$reason count=$backPressCount " +
                "windows=[${knownPackages.joinToString()}] top=${knownTop ?: "-"}",
        )
        return backOk
    }

    companion object {
        const val HWCTS_DISMISS_BACKS = 1

        fun dismissBackBudget(action: TargetAction, profileMax: Int): Int =
            if (action == TargetAction.HWCTS) HWCTS_DISMISS_BACKS else profileMax

        fun shouldWaitUntilGone(action: TargetAction): Boolean =
            action != TargetAction.HWCTS

        fun isAssistantTopWindow(topPackage: String?, ownPackage: String): Boolean =
            InterceptedAssistant.isInterceptedAssistant(topPackage, null, ownPackage)

        fun shouldPressDismissBack(topPackage: String?, ownPackage: String): Boolean =
            isAssistantTopWindow(topPackage, ownPackage)

        fun isDismissOvershoot(
            topPackage: String?,
            preAssistPackage: String?,
            ownPackage: String,
        ): Boolean {
            if (topPackage.isNullOrBlank()) return false
            if (InterceptedAssistant.isInterceptedAssistant(topPackage, null, ownPackage)) return false
            if (preAssistPackage != null &&
                topPackage.equals(preAssistPackage, ignoreCase = true)
            ) {
                return false
            }
            return true
        }

        fun shouldStopDismissBacks(
            topPackage: String?,
            @Suppress("UNUSED_PARAMETER") preAssistPackage: String?,
            ownPackage: String,
        ): Boolean {
            if (topPackage.isNullOrBlank()) return false
            return !InterceptedAssistant.isInterceptedAssistant(topPackage, null, ownPackage)
        }

        fun canDismissBack(
            actionLaunched: Boolean,
            backPressCount: Int,
            assistantPresent: Boolean,
            elapsedSinceLastBackMs: Long,
            maxBacks: Int,
            minIntervalMs: Long,
            assumePresent: Boolean = false,
        ): Boolean {
            if (actionLaunched) return false
            if (backPressCount >= maxBacks) return false
            if (!assumePresent && !assistantPresent) return false
            if (backPressCount > 0 && elapsedSinceLastBackMs < minIntervalMs) return false
            return true
        }

        fun containsAssistantWindow(packageNames: List<String?>, ownPackage: String): Boolean {
            val top = packageNames.firstOrNull { !it.isNullOrEmpty() }
            return InterceptedAssistant.isInterceptedAssistant(top, null, ownPackage)
        }
    }
}
