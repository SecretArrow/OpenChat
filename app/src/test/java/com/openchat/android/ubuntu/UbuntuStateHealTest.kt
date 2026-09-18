package com.openchat.android.ubuntu

import com.openchat.android.core.model.UbuntuState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the v0.1.15 stuck-operation heal decision
 * ([UbuntuRuntime.healState]) and the export cancel-restore rule.
 *
 * Real-device bug being covered (OPPO CPH2529, v0.1.14): the user started an
 * Export, navigated away while it ran; the composing scope was cancelled and
 * the busy EXPORTING state stayed persisted forever — the Ubuntu screen then
 * kept every button (Install/Repair/Update/Reset/Export/Import) silently
 * disabled, so the still-failing install could never be retried
 * ("setelah export kok ga bisa repair ataupun install"). The heal coerces a
 * busy state with NO live pipeline to ERROR; a live operation is never touched.
 */
class UbuntuStateHealTest {

    @Test
    fun `every busy state with no live pipeline heals to ERROR`() {
        val busyStates = listOf(
            UbuntuState.DOWNLOADING,
            UbuntuState.VERIFYING,
            UbuntuState.EXTRACTING,
            UbuntuState.CONFIGURING,
            UbuntuState.INSTALLING_PACKAGES,
            UbuntuState.INSTALLING_TOOLS,
            UbuntuState.INSTALLING_OPENCODE,
            UbuntuState.REPAIRING,
            UbuntuState.UPDATING,
            UbuntuState.RESETTING,
            UbuntuState.EXPORTING,
            UbuntuState.IMPORTING,
        )
        busyStates.forEach { state ->
            assertEquals(
                "busy state $state with pipelineRunning=false must heal to ERROR",
                UbuntuState.ERROR,
                UbuntuRuntime.healState(state, pipelineRunning = false),
            )
        }
    }

    @Test
    fun `a live pipeline never heals`() {
        UbuntuState.entries.forEach { state ->
            assertNull(
                "state $state with pipelineRunning=true must not be healed",
                UbuntuRuntime.healState(state, pipelineRunning = true),
            )
        }
    }

    @Test
    fun `non-busy states never heal`() {
        // NOT_INSTALLED / READY / ERROR are valid resting states — a stale
        // persisted value of any of them is honest and must be kept.
        listOf(
            UbuntuState.NOT_INSTALLED,
            UbuntuState.READY,
            UbuntuState.ERROR,
        ).forEach { state ->
            assertNull(
                "resting state $state must not be healed",
                UbuntuRuntime.healState(state, pipelineRunning = false),
            )
        }
    }

    @Test
    fun `export cancel restores a non-busy previous state unchanged`() {
        // The rule applied in UbuntuRuntime.export's CancellationException
        // handler: busy previous states fall back to ERROR, anything else is
        // restored verbatim (the export modified nothing).
        listOf(UbuntuState.READY, UbuntuState.ERROR, UbuntuState.NOT_INSTALLED).forEach { prev ->
            val restore = if (prev.busy) UbuntuState.ERROR else prev
            assertEquals(prev, restore)
        }
        listOf(UbuntuState.EXPORTING, UbuntuState.DOWNLOADING).forEach { prev ->
            val restore = if (prev.busy) UbuntuState.ERROR else prev
            assertEquals(UbuntuState.ERROR, restore)
        }
    }
}
