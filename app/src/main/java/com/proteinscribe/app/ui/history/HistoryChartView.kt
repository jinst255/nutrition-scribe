package com.proteinscribe.app.ui.history

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Custom chart View so the pinch gesture can be wired directly to Android's
 * ScaleGestureDetector for continuous, per-frame zoom updates (Section 12) rather
 * than a stepped slider. Embedded into Compose via AndroidView.
 */
class HistoryChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    var goalGrams: Double = 160.0
    var baselineGrams: Double = 112.0
    var onZoomChanged: ((Int) -> Unit)? = null
    var onPointTapped: ((BucketPoint) -> Unit)? = null

    private var zoom = 0
    private var points: List<BucketPoint> = emptyList()
    private var selectedIndex: Int? = null

    // Below baseline.
    private val belowBaselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B5433A"); style = Paint.Style.FILL
    }
    // Between baseline and goal.
    private val underGoalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3E6FA8"); style = Paint.Style.FILL
    }
    // At or above goal.
    private val atGoalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2F6F4F"); style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B6B66"); style = Paint.Style.STROKE; strokeWidth = 4f
    }
    private val goalLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B6B66"); style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(16f, 12f), 0f)
    }
    private val baselineLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#A8A79F"); style = Paint.Style.STROKE; strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(4f, 6f), 0f)
    }
    // White halo ring around the tapped point — reads against all three dot colors in both themes.
    private val selectionRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Spreading fingers (scaleFactor > 1) zooms in (fewer days); pinching zooms out.
                val delta = (detector.scaleFactor - 1f) * 60f
                zoom = (zoom - delta.toInt()).coerceIn(0, 100)
                onZoomChanged?.invoke(zoom)
                invalidate()
                return true
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (points.isEmpty()) return false
                val step = width.toFloat() / points.size
                val index = (e.x / step).toInt().coerceIn(0, points.size - 1)
                selectedIndex = index
                invalidate()
                onPointTapped?.invoke(points[index])
                return true
            }
        },
    )

    fun setZoom(value: Int) {
        zoom = value.coerceIn(0, 100)
        invalidate()
    }

    fun setPoints(newPoints: List<BucketPoint>) {
        points = newPoints
        if (selectedIndex != null && selectedIndex!! >= newPoints.size) selectedIndex = null
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        val maxValue = maxOf(goalGrams, points.maxOf { it.averageProtein }) * 1.1
        val paddingPx = 24f
        val plotWidth = width - paddingPx * 2
        val plotHeight = height - paddingPx * 2

        fun xFor(index: Int) = paddingPx + if (points.size > 1) plotWidth * index / (points.size - 1) else plotWidth / 2
        fun yFor(value: Double) = paddingPx + plotHeight * (1 - (value / maxValue)).toFloat()

        canvas.drawLine(paddingPx, yFor(goalGrams), width - paddingPx, yFor(goalGrams), goalLinePaint)
        canvas.drawLine(paddingPx, yFor(baselineGrams), width - paddingPx, yFor(baselineGrams), baselineLinePaint)

        var prevX = 0f
        var prevY = 0f
        points.forEachIndexed { index, point ->
            val x = xFor(index)
            val y = yFor(point.averageProtein)
            if (index > 0) canvas.drawLine(prevX, prevY, x, y, linePaint)
            val paint = when {
                point.averageProtein >= goalGrams -> atGoalPaint
                point.averageProtein >= baselineGrams -> underGoalPaint
                else -> belowBaselinePaint
            }
            canvas.drawCircle(x, y, 10f, paint)
            if (index == selectedIndex) {
                canvas.drawCircle(x, y, 16f, selectionRingPaint)
            }
            prevX = x
            prevY = y
        }
    }
}
