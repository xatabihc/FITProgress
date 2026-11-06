package ru.fitprogress.ai

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses the actual disabled-provider state, never toggles global location or inserts GPS points. */
class DisabledGpsTest {
    @Test fun serviceRejectsDisabledLocationWithoutCreatingOrLosingARun() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as FitApp
        check(app.isolatedTestStorage)
        assumeTrue(!app.getSystemService(LocationManager::class.java).isLocationEnabled)
        assumeTrue(app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        app.ready.await()
        val before = app.repository.snapshot().runs
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { RunService.launch.idle(); RunService.launch.request(true); RunService.command(it, RunService.START) }
                val state = withTimeout(15000) { RunService.launch.state.first { it.phase == RunLaunchPhase.ERROR } }
                assertEquals(state.toString(), RunLaunchError.LOCATION_DISABLED, state.error)
                assertEquals(before, app.repository.snapshot().runs)
            } finally {
                scenario.onActivity { it.stopService(Intent(it, RunService::class.java)) }
                RunService.launch.idle()
            }
        }
    }
}
