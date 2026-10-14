package com.graphite.app.feature.diagnostic

enum class InputModePolicy {
    ALLOW_ALL,            // Finger and Stylus both draw (fallback for phones or stylus-free testing)
    STYLUS_ONLY_FOR_INK,  // Only Stylus draws; Finger touches are rejected or reserved for navigation
    ACTIVE_PALM_GUARD     // Dynamically reject Finger touch if Stylus was active recently
}

class PalmRejectionFilter(
    var policy: InputModePolicy = InputModePolicy.ACTIVE_PALM_GUARD,
    var stylusTimeoutMs: Long = 650L
) {
    private var lastStylusEventTimestamp: Long = 0L

    fun shouldAcceptEvent(packet: StylusPacket): Boolean {
        val now = packet.timestamp

        when (packet.toolType) {
            StylusToolType.STYLUS, StylusToolType.ERASER -> {
                lastStylusEventTimestamp = now
                return true
            }
            StylusToolType.FINGER -> {
                return when (policy) {
                    InputModePolicy.ALLOW_ALL -> true
                    InputModePolicy.STYLUS_ONLY_FOR_INK -> false
                    InputModePolicy.ACTIVE_PALM_GUARD -> {
                        val elapsedSinceStylus = now - lastStylusEventTimestamp
                        // Reject finger touch if stylus was used within stylusTimeoutMs window
                        elapsedSinceStylus > stylusTimeoutMs
                    }
                }
            }
            else -> return true
        }
    }

    fun reset() {
        lastStylusEventTimestamp = 0L
    }
}
