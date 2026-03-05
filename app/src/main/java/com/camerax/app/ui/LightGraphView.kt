package com.camerax.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class LightGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7CFF7C")
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#44FFFFFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private val values = ArrayDeque<Float>()
    private val maxPoints = 60

    fun addValue(value: Float) {
        if (values.size >= maxPoints) values.removeFirst()
        values.addLast(value.coerceIn(0f, 255f))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val h = height.toFloat()
        val w = width.toFloat()

        canvas.drawLine(0f, h * 0.25f, w, h * 0.25f, gridPaint)
        canvas.drawLine(0f, h * 0.5f, w, h * 0.5f, gridPaint)
        canvas.drawLine(0f, h * 0.75f, w, h * 0.75f, gridPaint)

        if (values.size < 2) return

        val path = Path()
        val stepX = w / max(1, maxPoints - 1)
        values.forEachIndexed { index, sample ->
            val x = index * stepX
            val normalized = sample / 255f
            val y = h - (normalized * h)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)
    }
}
