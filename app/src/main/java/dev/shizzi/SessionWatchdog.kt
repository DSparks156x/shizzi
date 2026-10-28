package dev.shizzi

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/** What the session found when the watchdog asked about its downstream. */
sealed interface DownstreamHealth {
    object Up : DownstreamHealth
    object Awaiting : DownstreamHealth
    class Lost(val problem: String) : DownstreamHealth
}

class SessionWatchdog(
    private val expectedInterface: String,
    private val checkDownstream: () -> DownstreamHealth = { DownstreamHealth.Up },
    private val onDrift: (String) -> Unit,
) {

    private val inspector = UpstreamInspector()
    private val isRunning = AtomicBoolean(false)
    private var thread: Thread? = null

    private val tolerance = UpstreamTolerance(expectedInterface) { strike ->
        Log.i(TAG, strike)
        SessionLog.warn(strike)
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return

        thread = Thread({ monitor() }, "session-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        isRunning.set(false)

        val watcher = thread
        thread = null
        if (watcher != null && watcher != Thread.currentThread()) watcher.interrupt()
    }

    private fun monitor() {
        while (isRunning.get()) {
            val didSleep = runCatching { Thread.sleep(POLL_INTERVAL_MS) }.isSuccess
            if (!didSleep || !isRunning.get()) return

            val problem = checkUpstream()
            if (problem != null) {
                isRunning.set(false)
                Log.w(TAG, "tearing down: $problem")
                onDrift(problem)
                return
            }
        }
    }

    private fun checkUpstream(): String? {
        val observation = inspector.observe()
        val names = observation.liveInterfaceNames(expectedInterface)
        val reading = classifyUpstream(names, expectedInterface, observation.didTimeout)

        // Asked every check, not only on ABSENT, so the session also sees the
        // downstream come back once the upstream is healthy again.
        val health = checkDownstream()
        if (health is DownstreamHealth.Lost) return health.problem

        // Tethering picks no upstream while no downstream is up, and an absent
        // upstream forwards nothing, so waiting out a pulled cable is safe.
        if (reading == UpstreamReading.ABSENT && health == DownstreamHealth.Awaiting) {
            tolerance.forgive()
            return null
        }
        return tolerance.judge(reading, names)
    }

    private companion object {
        const val TAG = "SessionWatchdog"
        const val POLL_INTERVAL_MS = 5_000L
    }
}
