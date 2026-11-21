package com.rokid.renewcxrlsample.presentation

/** Main-thread gate: cancelled/previous attempts cannot start media or a presentation. */
class PresentationStartGate {
    private var generation = 0L
    private var active = false
    var preparing: Boolean = false
        private set
    private var serverReady = false

    fun begin(): Long? {
        if (active) return null
        generation++
        active = true
        preparing = true
        serverReady = false
        return generation
    }

    fun isCurrent(ticket: Long): Boolean = active && ticket == generation

    fun serverConfirmed(ticket: Long): Boolean {
        if (!isCurrent(ticket) || !preparing) return false
        serverReady = true
        return true
    }

    fun complete(ticket: Long, audioReady: Boolean, sttReady: Boolean): Boolean {
        if (!isCurrent(ticket) || !preparing || !serverReady || !audioReady || !sttReady) return false
        preparing = false
        return true
    }

    fun cancel() {
        generation++
        active = false
        preparing = false
        serverReady = false
    }
}
