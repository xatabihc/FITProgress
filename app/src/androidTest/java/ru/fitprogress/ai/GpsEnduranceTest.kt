package ru.fitprogress.ai

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in host test. Host locks screen and forces Doze after the ready marker. */
class GpsEnduranceTest {
    @Test fun activeSessionSurvivesBackgroundAndReopen(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Use tools/test-gps-endurance.py", args.getString("gpsEndurance") == "true")
        val seconds = requireNotNull(args.getString("enduranceSeconds")).toLong()
        require(seconds in 60..3600)
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage)
        check(app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        check(app.getSystemService(LocationManager::class.java).isLocationEnabled)
        app.ready.await()
        check(app.repository.currentRun() == null)
        val marker = File(app.filesDir, "qa-artifacts/gps-endurance-ready")
        marker.parentFile!!.mkdirs(); marker.delete()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { RunService.launch.idle(); RunService.launch.request(true); RunService.command(it, RunService.START) }
            val state = withTimeout(20000) { RunService.launch.state.first { it.phase in listOf(RunLaunchPhase.ACTIVE, RunLaunchPhase.ERROR) } }
            assertEquals(RunLaunchPhase.ACTIVE, state.phase)
            val initial = requireNotNull(app.repository.currentRun())
            // UI-suite wake flags must not contaminate this screen-off fixture.
            scenario.onActivity {
                it.setTurnScreenOn(false)
                it.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            scenario.moveToState(Lifecycle.State.CREATED)
            marker.writeText(initial.id)
            val before = android.os.SystemClock.elapsedRealtime()
            // Coroutine delay alone measures uptime; elapsedRealtime includes deep sleep.
            while(android.os.SystemClock.elapsedRealtime() - before < seconds * 1000) delay(1000)
            val persisted = requireNotNull(app.repository.currentRun())
            assertEquals(initial.id, persisted.id); assertEquals("active", persisted.status)
            // persisted timer, not merely the process-local clock, must advance through checkpoints.
            assertTrue("No background checkpoints", persisted.accumulatedMs >= initial.accumulatedMs + (seconds - 15) * 1000)
            assertTrue(android.os.SystemClock.elapsedRealtime() - before >= seconds * 1000)
            // The host exits forced idle and wakes the display before the user reopens UI.
            val power = app.getSystemService(android.os.PowerManager::class.java)
            withTimeout(30000) { while(power.isDeviceIdleMode || !power.isInteractive) delay(200) }
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { RunService.command(it, RunService.PAUSE) }
            val paused = withTimeout(10000) { app.repository.runs.first { it.any { r -> r.id == initial.id && r.status == "paused" } }.single { it.id == initial.id } }
            assertEquals(initial.id, paused.id)
            scenario.onActivity { RunService.command(it, RunService.RESUME) }
            withTimeout(10000) { app.repository.runs.first { it.any { r -> r.id == initial.id && r.status == "active" } } }
            Unit
        } finally {
            RunService.command(app, RunService.FINISH)
            withTimeout(10000) { while(app.repository.currentRun() != null) delay(100) }
            marker.delete(); scenario.close()
        }
    }
}
