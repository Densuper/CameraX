package com.camerax.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class DetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Box(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val isFace: Boolean
    )

    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#4CD964")
    }
    private val objectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#FFD95A")
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 28f
    }

    private val boxes = mutableListOf<Box>()

    fun updateBoxes(newBoxes: List<Box>) {
        boxes.clear()
        boxes.addAll(newBoxes)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (box in boxes) {
            val l = min(box.left, box.right).coerceIn(0f, width.toFloat())
            val t = min(box.top, box.bottom).coerceIn(0f, height.toFloat())
            val r = max(box.left, box.right).coerceIn(0f, width.toFloat())
            val b = max(box.top, box.bottom).coerceIn(0f, height.toFloat())
            val rect = RectF(l, t, r, b)
            val paint = if (box.isFace) facePaint else objectPaint
            canvas.drawRoundRect(rect, 14f, 14f, paint)
            canvas.drawText(if (box.isFace) "Face" else "Object", l + 8f, t - 10f, labelPaint)
        }
    }
}

