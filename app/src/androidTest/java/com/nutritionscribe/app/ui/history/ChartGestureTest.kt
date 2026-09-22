package com.nutritionscribe.app.ui.history

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nutritionscribe.app.ui.theme.NutritionScribeTheme
import com.nutritionscribe.shared.model.Metric
import com.nutritionscribe.shared.model.MetricGoal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The full-size History chart, driven with real multi-finger gestures.
 *
 * The viewport maths is unit-tested on the JVM; what this covers is the part
 * that can silently break — the gesture wiring. A pinch handler reading a stale
 * viewport, or a tap handler shadowed by the drag handler, still compiles and
 * still draws; it just stops responding to fingers.
 */
@RunWith(AndroidJUnit4::class)
class ChartGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val today = 20_000L
    private val first = today - 199
    private val days = (first..today).map { DayValue(it, 100.0 + (it % 7) * 10) }
    private val goal = MetricGoal(Metric.PROTEIN, enabled = true, target = 150.0)

    private var viewport by mutableStateOf(MetricHistory.latest(30.0, first, today))
    private var selected: Bucket? by mutableStateOf(null)

    private fun show() {
        compose.setContent {
            NutritionScribeTheme {
                val size = MetricHistory.bucketSizeFor(viewport.spanDays)
                InteractiveChart(
                    buckets = MetricHistory.buckets(days, size),
                    viewport = viewport,
                    goal = goal,
                    colors = standingColors(),
                    selected = selected,
                    onViewportChange = { viewport = it },
                    onSelect = { selected = it },
                    firstDay = first,
                    today = today,
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag("chart"),
                )
            }
        }
    }

    @Test
    fun spreadingTwoFingersZoomsIn() {
        show()
        compose.onNodeWithTag("chart").performTouchInput {
            pinch(
                start0 = center - Offset(40f, 0f), end0 = center - Offset(300f, 0f),
                start1 = center + Offset(40f, 0f), end1 = center + Offset(300f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue("expected fewer than 30 days, got ${viewport.spanDays}", viewport.spanDays < 30.0)
    }

    @Test
    fun pinchingTwoFingersTogetherZoomsOut() {
        show()
        compose.onNodeWithTag("chart").performTouchInput {
            pinch(
                start0 = center - Offset(300f, 0f), end0 = center - Offset(30f, 0f),
                start1 = center + Offset(300f, 0f), end1 = center + Offset(30f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue("expected more than 30 days, got ${viewport.spanDays}", viewport.spanDays > 30.0)
    }

    @Test
    fun draggingRightGoesBackInTime() {
        show()
        val before = viewport.endDay
        compose.onNodeWithTag("chart").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue("view should have moved back", viewport.endDay < before - 1)
        assertEquals("a drag must not change the zoom", 30.0, viewport.spanDays, 0.001)
    }

    @Test
    fun draggingLeftStopsAtToday() {
        show()
        compose.onNodeWithTag("chart").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(today + 1.0, viewport.endDay, 0.001)
    }

    @Test
    fun tappingABarSelectsThatDay() {
        show()
        compose.onNodeWithTag("chart").performTouchInput {
            // Just inside the right edge of the plot, which is the most recent day.
            click(Offset(width - 44.dp.toPx() - 4f, height / 2f))
        }
        compose.waitForIdle()
        assertNotNull(selected)
        assertEquals(today, selected!!.startEpochDay)
    }
}
