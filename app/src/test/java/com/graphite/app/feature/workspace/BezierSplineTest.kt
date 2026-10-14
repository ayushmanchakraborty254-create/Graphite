package com.graphite.app.feature.workspace

import com.graphite.app.feature.workspace.geometry.BezierSplineInterpolator
import com.graphite.app.feature.workspace.model.InkPoint
import org.junit.Assert.*
import org.junit.Test

class BezierSplineTest {

    @Test
    fun testEmptyPointsProducesZeroSegments() {
        val segments = BezierSplineInterpolator.computeSplineSegments(emptyList())
        assertTrue(segments.isEmpty())
    }

    @Test
    fun testSinglePointProducesZeroSegments() {
        val points = listOf(InkPoint(100f, 100f, 0.5f))
        val segments = BezierSplineInterpolator.computeSplineSegments(points)
        assertTrue(segments.isEmpty())
    }

    @Test
    fun testTwoPointsProducesSingleSegment() {
        val points = listOf(
            InkPoint(100f, 100f, 0.5f),
            InkPoint(200f, 200f, 0.5f)
        )
        val segments = BezierSplineInterpolator.computeSplineSegments(points)
        assertEquals(1, segments.size)

        val seg = segments[0]
        assertEquals(100f, seg.startX, 0.001f)
        assertEquals(100f, seg.startY, 0.001f)
        assertEquals(200f, seg.endX, 0.001f)
        assertEquals(200f, seg.endY, 0.001f)
    }

    @Test
    fun testMultiPointCatmullRomSplineSegmentContinuity() {
        val points = listOf(
            InkPoint(10f, 10f, 0.2f),
            InkPoint(50f, 80f, 0.5f),
            InkPoint(100f, 40f, 0.8f),
            InkPoint(150f, 120f, 0.4f),
            InkPoint(200f, 90f, 0.6f)
        )
        val segments = BezierSplineInterpolator.computeSplineSegments(points)
        // 5 points should yield 4 connected cubic segments
        assertEquals(4, segments.size)

        // Verify C0 continuity (end of segment i matches start of segment i+1)
        for (i in 0 until segments.size - 1) {
            assertEquals(segments[i].endX, segments[i + 1].startX, 0.001f)
            assertEquals(segments[i].endY, segments[i + 1].startY, 0.001f)
        }

        // Verify first and last endpoints
        assertEquals(points[0].x, segments[0].startX, 0.001f)
        assertEquals(points[0].y, segments[0].startY, 0.001f)
        assertEquals(points.last().x, segments.last().endX, 0.001f)
        assertEquals(points.last().y, segments.last().endY, 0.001f)
    }

    @Test
    fun testPressureModulatedStrokeWidth() {
        val baseWidth = 10f

        val minWidth = BezierSplineInterpolator.calculateEffectiveWidth(baseWidth, 0.05f)
        val midWidth = BezierSplineInterpolator.calculateEffectiveWidth(baseWidth, 0.5f)
        val maxWidth = BezierSplineInterpolator.calculateEffectiveWidth(baseWidth, 1.0f)

        // Effective width should scale monotonically with pressure
        assertTrue("Min width ($minWidth) should be less than mid width ($midWidth)", minWidth < midWidth)
        assertTrue("Mid width ($midWidth) should be less than max width ($maxWidth)", midWidth < maxWidth)
        assertEquals(baseWidth * (0.4f + 0.9f * 1.0f), maxWidth, 0.001f)
    }
}
