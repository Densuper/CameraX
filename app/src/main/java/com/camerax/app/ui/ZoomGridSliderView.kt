package com.camerax.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

class ZoomGridSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val outerRect = RectF()
    private val innerRect = RectF()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5C000000")
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#90FFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66FFFFFF")
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6FFFFFF")
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCFFFFFF")
        textAlign = Paint.Align.CENTER
        textSize = dp(11f)
    }

    private var zoomFraction = 0f
    private var onZoomChanged: ((Float) -> Unit)? = null

    fun setOnZoomChangedListener(listener: (Float) -> Unit) {
        onZoomChanged = listener
    }

    fun setZoomFraction(value: Float) {
        zoomFraction = value.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val strokeInset = strokePaint.strokeWidth / 2f + dp(0.5f)
        outerRect.set(
            strokeInset,
            strokeInset,
            width.toFloat() - strokeInset,
            height.toFloat() - strokeInset
        )
        val radius = min(outerRect.width(), outerRect.height()) * 0.22f

        val innerInsetX = dp(6f)
        val innerInsetY = dp(8f)
        innerRect.set(
            outerRect.left + innerInsetX,
            outerRect.top + innerInsetY,
            outerRect.right - innerInsetX,
            outerRect.bottom - innerInsetY
        )

        canvas.drawRoundRect(outerRect, radius, radius, fillPaint)
        canvas.drawRoundRect(outerRect, radius, radius, strokePaint)

        val dotSpacing = dp(8f)
        var y = innerRect.top + dotSpacing * 0.5f
        while (y < innerRect.bottom) {
            var x = innerRect.left + dotSpacing * 0.5f
            while (x < innerRect.right) {
                canvas.drawCircle(x, y, dp(0.9f), dotPaint)
                x += dotSpacing
            }
            y += dotSpacing
        }

        val handleY = innerRect.bottom - (innerRect.height() * zoomFraction)
        canvas.drawRoundRect(
            innerRect.left + dp(3f),
            handleY - dp(2f),
            innerRect.right - dp(3f),
            handleY + dp(2f),
            dp(3f),
            dp(3f),
            handlePaint
        )

        canvas.drawText("+", (width / 2f), dp(15f), textPaint)
        canvas.drawText("-", (width / 2f), height - dp(10f), textPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val top = dp(8f)
                val bottom = height - dp(8f)
                val clampedY = event.y.coerceIn(top, bottom)
                val ratio = 1f - ((clampedY - top) / (bottom - top))
                val stepped = ((ratio * 100f).roundToInt() / 100f).coerceIn(0f, 1f)
                if (stepped != zoomFraction) {
                    zoomFraction = stepped
                    invalidate()
                    onZoomChanged?.invoke(zoomFraction)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
