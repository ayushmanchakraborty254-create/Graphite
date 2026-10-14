package com.graphite.app.feature.diagnostic

import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test

class StylusDiagnosticTest {

    @Test
    fun testToolTypeMapping() {
        assertEquals(StylusToolType.STYLUS, StylusToolType.fromMotionEvent(MotionEvent.TOOL_TYPE_STYLUS))
        assertEquals(StylusToolType.ERASER, StylusToolType.fromMotionEvent(MotionEvent.TOOL_TYPE_ERASER))
        assertEquals(StylusToolType.FINGER, StylusToolType.fromMotionEvent(MotionEvent.TOOL_TYPE_FINGER))
        assertEquals(StylusToolType.MOUSE, StylusToolType.fromMotionEvent(MotionEvent.TOOL_TYPE_MOUSE))
        assertEquals(StylusToolType.UNKNOWN, StylusToolType.fromMotionEvent(MotionEvent.TOOL_TYPE_UNKNOWN))
    }

    @Test
    fun testActionTypeMapping() {
        assertEquals(MotionActionType.DOWN, MotionActionType.fromMotionEvent(MotionEvent.ACTION_DOWN))
        assertEquals(MotionActionType.MOVE, MotionActionType.fromMotionEvent(MotionEvent.ACTION_MOVE))
        assertEquals(MotionActionType.UP, MotionActionType.fromMotionEvent(MotionEvent.ACTION_UP))
        assertEquals(MotionActionType.CANCEL, MotionActionType.fromMotionEvent(MotionEvent.ACTION_CANCEL))
        assertEquals(MotionActionType.HOVER_ENTER, MotionActionType.fromMotionEvent(MotionEvent.ACTION_HOVER_ENTER))
        assertEquals(MotionActionType.HOVER_MOVE, MotionActionType.fromMotionEvent(MotionEvent.ACTION_HOVER_MOVE))
        assertEquals(MotionActionType.HOVER_EXIT, MotionActionType.fromMotionEvent(MotionEvent.ACTION_HOVER_EXIT))
    }

    @Test
    fun testPalmRejectionFilterPolicies() {
        val filter = PalmRejectionFilter(policy = InputModePolicy.ALLOW_ALL)
        val fingerPacket = createPacket(toolType = StylusToolType.FINGER, timestamp = 1000L)
        val stylusPacket = createPacket(toolType = StylusToolType.STYLUS, timestamp = 1000L)

        // ALLOW_ALL allows both
        assertTrue(filter.shouldAcceptEvent(fingerPacket))
        assertTrue(filter.shouldAcceptEvent(stylusPacket))

        // STYLUS_ONLY_FOR_INK rejects finger
        filter.policy = InputModePolicy.STYLUS_ONLY_FOR_INK
        assertFalse(filter.shouldAcceptEvent(fingerPacket))
        assertTrue(filter.shouldAcceptEvent(stylusPacket))

        // ACTIVE_PALM_GUARD rejects finger within timeout window after stylus
        filter.policy = InputModePolicy.ACTIVE_PALM_GUARD
        filter.stylusTimeoutMs = 500L

        // Stylus touches at t=1000
        filter.shouldAcceptEvent(createPacket(toolType = StylusToolType.STYLUS, timestamp = 1000L))

        // Finger touch at t=1200 (200ms elapsed < 500ms timeout) -> must be rejected as palm
        val simultaneousPalm = createPacket(toolType = StylusToolType.FINGER, timestamp = 1200L)
        assertFalse("Finger touch within 200ms of stylus touch should be rejected as palm", filter.shouldAcceptEvent(simultaneousPalm))

        // Finger touch at t=1600 (600ms elapsed > 500ms timeout) -> accepted
        val normalTouch = createPacket(toolType = StylusToolType.FINGER, timestamp = 1600L)
        assertTrue("Finger touch after stylus timeout should be accepted", filter.shouldAcceptEvent(normalTouch))
    }

    @Test
    fun testCapabilityUpdateLogic() {
        var summary = HardwareCapabilitySummary()

        val stylusPacket = createPacket(
            toolType = StylusToolType.STYLUS,
            rawPressure = 0.65f,
            tiltDegrees = 45.0f,
            hoverDistance = 5.0f,
            isPrimaryButtonPressed = true
        )

        summary = StylusEventParser.updateCapabilities(
            current = summary,
            packet = stylusPacket,
            wasPalmRejected = false
        )

        assertTrue(summary.hasStylusInputSeen)
        assertTrue(summary.hasPressureSupport)
        assertTrue(summary.hasTiltSupport)
        assertTrue(summary.hasHoverSupport)
        assertTrue(summary.hasBarrelButtonSupport)
        assertEquals(0.65f, summary.maxPressureSeen, 0.001f)
        assertEquals(45.0f, summary.maxTiltDegreesSeen, 0.001f)
        assertEquals(1L, summary.totalEventsProcessed)
        assertEquals(0, summary.palmTouchesRejectedCount)

        // Palm event rejected
        val palmPacket = createPacket(toolType = StylusToolType.FINGER)
        summary = StylusEventParser.updateCapabilities(
            current = summary,
            packet = palmPacket,
            wasPalmRejected = true
        )

        assertEquals(1, summary.palmTouchesRejectedCount)
        assertEquals(2L, summary.totalEventsProcessed)
    }

    private fun createPacket(
        toolType: StylusToolType,
        timestamp: Long = 1000L,
        rawPressure: Float = 1.0f,
        tiltDegrees: Float = 0.0f,
        hoverDistance: Float = 0.0f,
        isPrimaryButtonPressed: Boolean = false,
        isEraser: Boolean = toolType == StylusToolType.ERASER
    ): StylusPacket {
        return StylusPacket(
            timestamp = timestamp,
            action = MotionActionType.MOVE,
            toolType = toolType,
            pointerId = 0,
            pointerCount = 1,
            x = 100f,
            y = 100f,
            rawPressure = rawPressure,
            normalizedPressure = rawPressure.coerceIn(0f, 1f),
            tiltRadians = Math.toRadians(tiltDegrees.toDouble()).toFloat(),
            tiltDegrees = tiltDegrees,
            orientationRadians = 0f,
            hoverDistance = hoverDistance,
            isPrimaryButtonPressed = isPrimaryButtonPressed,
            isSecondaryButtonPressed = false,
            isEraser = isEraser
        )
    }
}
