package ru.fitprogress.ai

import org.junit.Test
import org.junit.Assert.*

class RunLaunchTest {
    @Test fun failedFusedSubscriptionCanFallBackToEnabledSystemGps() {
        assertTrue(GpsFallbackPolicy.canUsePlatformGps(true, true, true, false))
    }
    @Test fun fallbackNeverBypassesMissingPermissionOrDisabledProvider() {
        assertFalse(GpsFallbackPolicy.canUsePlatformGps(false, true, true, false))
        assertFalse(GpsFallbackPolicy.canUsePlatformGps(true, false, true, false))
        assertFalse(GpsFallbackPolicy.canUsePlatformGps(true, true, false, false))
    }
    @Test fun fallbackCannotRegisterPlatformGpsTwice() {
        assertFalse(GpsFallbackPolicy.canUsePlatformGps(true, true, true, true))
    }
    @Test fun runningScreenNeverShowsStaleGpsAccuracyAsCurrent() {
        val point = GeoPoint(0.0, 0.0, 7f, 100_000, elapsedMs = 1_000, boot = 1)
        assertEquals("Нет свежего GPS", GpsSignalStatus.accuracyLabel(null, ClockStamp(100_000, 1_000, 1)))
        assertEquals("±7 м", GpsSignalStatus.accuracyLabel(point, ClockStamp(500_000, 6_000, 1)))
        assertEquals("Нет свежего GPS", GpsSignalStatus.accuracyLabel(point, ClockStamp(500_000, 25_001, 1)))
        assertEquals("Нет свежего GPS", GpsSignalStatus.accuracyLabel(point.copy(accuracy = 100f), ClockStamp(100_000, 2_000, 1)))
    }
    @Test fun successfulLaunchIsAcknowledgedWithoutFirstFix() {
        val c = RunLaunchController(); var starts = 0
        assertTrue(c.request(true)); assertEquals(RunLaunchPhase.STARTING, c.state.value.phase)
        assertTrue(c.dispatch(true) { starts++ }); c.active()
        assertEquals(1, starts); assertEquals(RunLaunchPhase.ACTIVE, c.state.value.phase)
        val start = ClockStamp(100000, 1000, 1)
        val r = RunTimers.resume(Run(status = "paused"), start)
        assertTrue(r.points.isEmpty()); assertEquals(0.0, r.distance, 0.0)
        assertEquals(5000L, RunTimers.elapsed(r, start.copy(elapsedMs = 6000)))
    }
    @Test fun missingPermissionRequestsSystemPromptBeforeService() {
        val c = RunLaunchController(); assertTrue(c.request(false)); assertEquals(RunLaunchPhase.PERMISSION, c.state.value.phase)
        assertFalse(c.dispatch(true) { fail("No location permission") })
        assertTrue(c.permissionResult(true)); assertTrue(c.dispatch(true) {})
    }
    @Test fun permissionDenialHasVisibleErrorAndNoStart() {
        val c = RunLaunchController(); c.request(false); assertFalse(c.permissionResult(false))
        assertEquals(RunLaunchError.PERMISSION, c.state.value.error); assertFalse(c.state.value.pending)
        assertFalse(c.dispatch(true) { fail("Denied") })
    }
    @Test fun disabledLocationDoesNotDispatchAndCanRetry() {
        val c = RunLaunchController(); c.request(true)
        assertFalse(c.dispatch(false) { fail("Provider disabled") }); assertEquals(RunLaunchError.LOCATION_DISABLED, c.state.value.error)
        assertTrue(c.request(true)); assertTrue(c.dispatch(true) {})
    }
    @Test fun foregroundSecurityFailureIsNotSilent() {
        val c = RunLaunchController(); c.request(true); assertFalse(c.dispatch(true) { throw SecurityException("fixture") })
        assertEquals(RunLaunchError.SERVICE, c.state.value.error); assertEquals("SecurityException", c.state.value.diagnostic)
        assertTrue(c.request(true))
    }
    @Test fun foregroundBackgroundRestrictionIsNotSilent() {
        val c = RunLaunchController(); c.request(true); assertFalse(c.dispatch(true) { throw IllegalStateException("fixture") })
        assertEquals("IllegalStateException", c.state.value.diagnostic)
    }
    @Test fun repeatedTapsDuringPermissionAndStartupDoNotStartTwice() {
        val c = RunLaunchController(); assertTrue(c.request(false)); repeat(10) { assertFalse(c.request(false)) }
        c.permissionResult(true); repeat(10) { assertFalse(c.request(true)) }; c.active(); assertFalse(c.request(true))
    }
    @Test fun asyncSubscriptionFailureAllowsRetry() {
        val c = RunLaunchController(); c.request(true); c.dispatch(true) {}; c.active(); c.fail(RunLaunchError.SUBSCRIPTION, "ApiException")
        assertEquals(RunLaunchPhase.ERROR, c.state.value.phase); assertTrue(c.request(true))
    }
    @Test fun timeoutUnblocksRetry() {
        val c = RunLaunchController(); c.request(true); c.timeout(c.state.value.attempt)
        assertEquals(RunLaunchError.TIMEOUT, c.state.value.error); assertTrue(c.request(true))
    }
    @Test fun oldTimeoutCannotFailANewerAttempt() {
        val c = RunLaunchController(); c.request(true); val first = c.state.value.attempt; c.fail(RunLaunchError.SERVICE)
        c.request(true); c.timeout(first); assertEquals(RunLaunchPhase.STARTING, c.state.value.phase)
        c.active(); c.timeout(c.state.value.attempt); assertEquals(RunLaunchPhase.ACTIVE, c.state.value.phase)
    }
    @Test fun pauseEnablesNewResumeRequest() {
        val c = RunLaunchController(); c.request(true); c.active(); c.idle(); assertTrue(c.request(true))
    }
    @Test fun unsolicitedPermissionCallbackCannotLaunchService() {
        val c = RunLaunchController(); assertFalse(c.permissionResult(true)); assertEquals(RunLaunchPhase.IDLE, c.state.value.phase)
    }
}
