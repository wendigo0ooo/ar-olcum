package com.wendigo.arolcum.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.wendigo.arolcum.R
import com.wendigo.arolcum.ar.MeasureState
import com.wendigo.arolcum.core.UnitSystem
import com.wendigo.arolcum.core.Units
import com.wendigo.arolcum.core.Vec3

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var state: MeasureState? = null
        set(value) {
            field = value
            invalidate()
        }

    var unitSystem: UnitSystem = UnitSystem.METRIC
        set(value) {
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.measure_crosshair)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val pointOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.measure_line)
        style = Paint.Style.FILL
    }
    private val pointInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.measure_accent)
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.measure_line)
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.measure_label_bg)
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.overlay_text)
        textSize = 16f * scaledDensity
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val currentState = state
        if (currentState != null && width > 0 && height > 0) {
            drawCrosshair(canvas)
            drawMeasurement(canvas, currentState)
        }
        if (isShown && windowVisibility == VISIBLE) {
            postInvalidateOnAnimation()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            invalidate()
        }
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = dp(12f)
        canvas.drawCircle(cx, cy, radius, crosshairPaint)
        canvas.drawCircle(cx, cy, dp(2f), crosshairPaint)
        canvas.drawLine(cx - radius * 1.6f, cy, cx - radius * 0.7f, cy, crosshairPaint)
        canvas.drawLine(cx + radius * 0.7f, cy, cx + radius * 1.6f, cy, crosshairPaint)
        canvas.drawLine(cx, cy - radius * 1.6f, cx, cy - radius * 0.7f, crosshairPaint)
        canvas.drawLine(cx, cy + radius * 0.7f, cx, cy + radius * 1.6f, crosshairPaint)
    }

    private fun drawMeasurement(canvas: Canvas, currentState: MeasureState) {
        val frame = currentState.frame
        val snapshot = frame.snapshot
        if (snapshot.points.isEmpty()) return

        val viewProjection = frame.viewProjection
        val viewportWidth = frame.viewportWidth.takeIf { it > 0 } ?: width
        val viewportHeight = frame.viewportHeight.takeIf { it > 0 } ?: height
        val projected = ArrayList<PointF>(snapshot.points.size)
        for (point in snapshot.points) {
            val projectedPoint = project(point, viewProjection, viewportWidth, viewportHeight)
                ?: continue
            projected.add(
                PointF(
                    projectedPoint.x * width.toFloat() / viewportWidth.toFloat(),
                    projectedPoint.y * height.toFloat() / viewportHeight.toFloat()
                )
            )
        }
        if (projected.isEmpty()) return

        if (projected.size >= 2) {
            val first = projected[0]
            val second = projected[1]
            canvas.drawLine(first.x, first.y, second.x, second.y, linePaint)
        }

        for (point in projected) {
            canvas.drawCircle(point.x, point.y, dp(12f), pointOuterPaint)
            canvas.drawCircle(point.x, point.y, dp(8f), pointInnerPaint)
        }

        if (projected.size >= 2) {
            drawLabel(canvas, projected[0], projected[1], snapshot.distanceMeters)
        }
    }

    private fun drawLabel(canvas: Canvas, first: PointF, second: PointF, distance: Double) {
        val text = Units.formatLength(distance, unitSystem)
        val textWidth = labelTextPaint.measureText(text)
        val metrics = labelTextPaint.fontMetrics
        val textHeight = metrics.descent - metrics.ascent
        val centerX = (first.x + second.x) / 2f
        val centerY = (first.y + second.y) / 2f
        val paddingX = dp(10f)
        val paddingY = dp(6f)

        val rect = RectF(
            centerX - textWidth / 2f - paddingX,
            centerY - textHeight / 2f - paddingY,
            centerX + textWidth / 2f + paddingX,
            centerY + textHeight / 2f + paddingY
        )
        canvas.drawRoundRect(rect, dp(12f), dp(12f), labelBgPaint)
        val baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, centerX, baseline, labelTextPaint)
    }

    private fun project(point: Vec3, viewProjection: FloatArray, width: Int, height: Int): PointF? {
        if (viewProjection.size < 16) return null
        val x = point.x.toFloat()
        val y = point.y.toFloat()
        val z = point.z.toFloat()

        val clipX =
            viewProjection[0] * x + viewProjection[4] * y + viewProjection[8] * z + viewProjection[12]
        val clipY =
            viewProjection[1] * x + viewProjection[5] * y + viewProjection[9] * z + viewProjection[13]
        val clipW =
            viewProjection[3] * x + viewProjection[7] * y + viewProjection[11] * z + viewProjection[15]

        if (clipW <= 0.0001f) return null

        val ndcX = clipX / clipW
        val ndcY = clipY / clipW
        return PointF(
            (ndcX * 0.5f + 0.5f) * width,
            (1f - (ndcY * 0.5f + 0.5f)) * height
        )
    }

    private fun dp(value: Float): Float = value * density
}
