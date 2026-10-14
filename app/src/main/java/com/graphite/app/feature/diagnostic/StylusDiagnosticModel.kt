package com.graphite.app.feature.diagnostic

import android.view.MotionEvent

enum class StylusToolType {
    STYLUS,
    ERASER,
    FINGER,
    MOUSE,
    UNKNOWN;

    companion object {
        fun fromMotionEvent(toolType: Int): StylusToolType {
            return when (toolType) {
                MotionEvent.TOOL_TYPE_STYLUS -> STYLUS
                MotionEvent.TOOL_TYPE_ERASER -> ERASER
                MotionEvent.TOOL_TYPE_FINGER -> FINGER
                MotionEvent.TOOL_TYPE_MOUSE -> MOUSE
                else -> UNKNOWN
            }
        }
    }
}

enum class MotionActionType {
    DOWN,
    MOVE,
    UP,
    CANCEL,
    HOVER_ENTER,
    HOVER_MOVE,
    HOVER_EXIT,
    OTHER;

    companion object {
        fun fromMotionEvent(actionMasked: Int): MotionActionType {
            return when (actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> DOWN
                MotionEvent.ACTION_MOVE -> MOVE
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> UP
                MotionEvent.ACTION_CANCEL -> CANCEL
                MotionEvent.ACTION_HOVER_ENTER -> HOVER_ENTER
                MotionEvent.ACTION_HOVER_MOVE -> HOVER_MOVE
                MotionEvent.ACTION_HOVER_EXIT -> HOVER_EXIT
                else -> OTHER
            }
        }
    }
}

data class StylusPacket(
    val timestamp: Long,
    val action: MotionActionType,
    val toolType: StylusToolType,
    val pointerId: Int,
    val pointerCount: Int,
    val x: Float,
    val y: Float,
    val rawPressure: Float,
    val normalizedPressure: Float,
    val tiltRadians: Float,
    val tiltDegrees: Float,
    val orientationRadians: Float,
    val hoverDistance: Float,
    val isPrimaryButtonPressed: Boolean,
    val isSecondaryButtonPressed: Boolean,
    val isEraser: Boolean
)

data class HardwareCapabilitySummary(
    val hasStylusInputSeen: Boolean = false,
    val hasPressureSupport: Boolean = false,
    val minPressureSeen: Float = 1.0f,
    val maxPressureSeen: Float = 0.0f,
    val hasTiltSupport: Boolean = false,
    val maxTiltDegreesSeen: Float = 0.0f,
    val hasHoverSupport: Boolean = false,
    val hasBarrelButtonSupport: Boolean = false,
    val hasEraserToolSeen: Boolean = false,
    val palmTouchesRejectedCount: Int = 0,
    val totalEventsProcessed: Long = 0
)

object StylusEventParser {

    fun parse(event: MotionEvent, pointerIndex: Int = 0): StylusPacket {
        val action = MotionActionType.fromMotionEvent(event.actionMasked)
        val toolTypeInt = if (pointerIndex < event.pointerCount) event.getToolType(pointerIndex) else MotionEvent.TOOL_TYPE_UNKNOWN
        val toolType = StylusToolType.fromMotionEvent(toolTypeInt)

        val pointerId = if (pointerIndex < event.pointerCount) event.getPointerId(pointerIndex) else 0
        val x = if (pointerIndex < event.pointerCount) event.getX(pointerIndex) else 0f
        val y = if (pointerIndex < event.pointerCount) event.getY(pointerIndex) else 0f

        // Pressure reading with fallback: standard capacitive touch often returns 1.0f or 0.0f
        val rawPressure = if (pointerIndex < event.pointerCount) event.getPressure(pointerIndex) else 1.0f
        val normalizedPressure = rawPressure.coerceIn(0.0f, 1.0f)

        // Stylus tilt: AXIS_TILT returns radians (0 is perpendicular to screen, pi/2 is parallel)
        val tiltRadians = if (pointerIndex < event.pointerCount) {
            event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex)
        } else 0f
        val tiltDegrees = Math.toDegrees(tiltRadians.toDouble()).toFloat()

        // Stylus orientation: AXIS_ORIENTATION returns radians (-pi to +pi)
        val orientationRadians = if (pointerIndex < event.pointerCount) {
            event.getAxisValue(MotionEvent.AXIS_ORIENTATION, pointerIndex)
        } else 0f

        // Stylus hover distance: AXIS_DISTANCE (0 when touching, positive when hovering)
        val hoverDistance = if (pointerIndex < event.pointerCount) {
            event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointerIndex)
        } else 0f

        val buttonState = event.buttonState
        val isPrimaryButtonPressed = (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_SECONDARY) != 0
        val isSecondaryButtonPressed = (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
                (buttonState and MotionEvent.BUTTON_TERTIARY) != 0
        val isEraser = toolType == StylusToolType.ERASER

        return StylusPacket(
            timestamp = event.eventTime,
            action = action,
            toolType = toolType,
            pointerId = pointerId,
            pointerCount = event.pointerCount,
            x = x,
            y = y,
            rawPressure = rawPressure,
            normalizedPressure = normalizedPressure,
            tiltRadians = tiltRadians,
            tiltDegrees = tiltDegrees,
            orientationRadians = orientationRadians,
            hoverDistance = hoverDistance,
            isPrimaryButtonPressed = isPrimaryButtonPressed,
            isSecondaryButtonPressed = isSecondaryButtonPressed,
            isEraser = isEraser
        )
    }

    fun updateCapabilities(
        current: HardwareCapabilitySummary,
        packet: StylusPacket,
        wasPalmRejected: Boolean
    ): HardwareCapabilitySummary {
        val isStylus = packet.toolType == StylusToolType.STYLUS || packet.toolType == StylusToolType.ERASER
        val hasPressure = current.hasPressureSupport || (isStylus && (packet.rawPressure > 0.01f && packet.rawPressure != 1.0f))
        val minPressure = if (packet.rawPressure > 0.001f) minOf(current.minPressureSeen, packet.rawPressure) else current.minPressureSeen
        val maxPressure = maxOf(current.maxPressureSeen, packet.rawPressure)
        val hasTilt = current.hasTiltSupport || (isStylus && packet.tiltDegrees > 0.1f)
        val maxTilt = maxOf(current.maxTiltDegreesSeen, packet.tiltDegrees)
        val hasHover = current.hasHoverSupport || (packet.hoverDistance > 0.01f || packet.action == MotionActionType.HOVER_MOVE)
        val hasButton = current.hasBarrelButtonSupport || packet.isPrimaryButtonPressed || packet.isSecondaryButtonPressed
        val hasEraser = current.hasEraserToolSeen || packet.isEraser
        val palmCount = current.palmTouchesRejectedCount + (if (wasPalmRejected) 1 else 0)

        return current.copy(
            hasStylusInputSeen = current.hasStylusInputSeen || isStylus,
            hasPressureSupport = hasPressure,
            minPressureSeen = minPressure,
            maxPressureSeen = maxPressure,
            hasTiltSupport = hasTilt,
            maxTiltDegreesSeen = maxTilt,
            hasHoverSupport = hasHover,
            hasBarrelButtonSupport = hasButton,
            hasEraserToolSeen = hasEraser,
            palmTouchesRejectedCount = palmCount,
            totalEventsProcessed = current.totalEventsProcessed + 1
        )
    }
}
