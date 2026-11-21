package com.rokid.renewcxrlsample.presentation

import org.junit.Assert.*
import org.junit.Test

class PresentationStartGateTest {
    @Test fun allThreeDependenciesAreRequired() {
        val gate = PresentationStartGate()
        val id = gate.begin()!!
        assertFalse(gate.complete(id, true, true))
        assertTrue(gate.serverConfirmed(id))
        assertFalse(gate.complete(id, false, true))
        assertFalse(gate.complete(id, true, false))
        assertTrue(gate.preparing)
        assertTrue(gate.complete(id, true, true))
        assertFalse(gate.preparing)
        assertFalse(gate.complete(id, true, true))
    }

    @Test fun duplicateStartIsRejectedDuringPreparationAndRunning() {
        val gate = PresentationStartGate()
        val id = gate.begin()!!
        assertNull(gate.begin())
        gate.serverConfirmed(id)
        gate.complete(id, true, true)
        assertNull(gate.begin())
        gate.cancel()
        assertNotNull(gate.begin())
    }

    @Test fun cancelledServerResponseCannotStartMedia() {
        val gate = PresentationStartGate()
        val old = gate.begin()!!
        gate.cancel()
        assertFalse(gate.isCurrent(old))
        assertFalse(gate.serverConfirmed(old))
        assertFalse(gate.complete(old, true, true))
    }

    @Test fun cancelledAudioPreparationCannotCompleteNewAttempt() {
        val gate = PresentationStartGate()
        val old = gate.begin()!!
        gate.serverConfirmed(old)
        gate.cancel()
        val current = gate.begin()!!
        assertFalse(gate.serverConfirmed(old))
        assertFalse(gate.complete(old, true, true))
        assertFalse(gate.complete(current, true, true))
        gate.serverConfirmed(current)
        assertTrue(gate.complete(current, true, true))
    }

    @Test fun stoppedPresentationRejectsLateCallbacks() {
        val gate = PresentationStartGate()
        val id = gate.begin()!!
        gate.serverConfirmed(id)
        gate.complete(id, true, true)
        gate.cancel()
        assertFalse(gate.isCurrent(id))
        assertFalse(gate.complete(id, true, true))
    }
}
