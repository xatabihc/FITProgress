package ru.fitprogress.ai

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in only: uses REAL location hardware in isolated storage. Never invents a GPS fix. */
class GpsServiceStartTest {
    @Test fun foregroundServiceStartsAndClockRunsBeforeAnyRequiredFix() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Pass -e gpsService true to explicitly allow real GPS", InstrumentationRegistry.getArguments().getString("gpsService") == "true")
        val app = instrumentation.targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage) { "Use FitTestRunner, never production history" }
        assumeTrue(app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        assumeTrue(app.getSystemService(LocationManager::class.java).isLocationEnabled)
        app.ready.await()
        check(app.repository.currentRun() == null) { "Finish existing isolated fixture before test" }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { RunService.launch.idle(); RunService.launch.request(true); RunService.command(it, RunService.START) }
            val started = withTimeout(20000) { RunService.launch.state.first { it.phase in listOf(RunLaunchPhase.ACTIVE, RunLaunchPhase.ERROR) } }
            assertEquals(started.toString(), RunLaunchPhase.ACTIVE, started.phase)
            val run = requireNotNull(app.repository.currentRun()); assertEquals("active", run.status)
            val before = RunTimers.elapsed(run, AndroidClock.read(app)); delay(1200)
            assertTrue(RunTimers.elapsed(run, AndroidClock.read(app)) >= before + 1000)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.any { it.id == 42 && it.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 })
        } finally {
            scenario.onActivity { RunService.command(it, RunService.FINISH) }
            withTimeout(10000) { while(app.repository.currentRun() != null) delay(100) }
            scenario.close()
        }
    }
}
