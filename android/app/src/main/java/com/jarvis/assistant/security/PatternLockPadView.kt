package com.jarvis.assistant.security

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Interactive 3x3 visual pattern drawing pad for visible PIN/Pattern setup.
 * Dots are indexed 1..9 (top-left = 1, bottom-right = 9).
 */
class PatternLockPadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onPatternChanged: ((List<Int>) -> Unit)? = null

    private val selectedDots = mutableListOf<Int>()
    private var currentTouchX = -1f
    private var currentTouchY = -1f
    private var isDragging = false

    private val bgRect = RectF()
    private val linePath = Path()

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#141D2B")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4400E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val dotIdlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A99AD")
        style = Paint.Style.FILL
    }

    private val dotActiveRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5500E5FF")
        style = Paint.Style.FILL
    }

    private val dotActiveCorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD700")
        style = Paint.Style.FILL
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 9f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val dotLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8A99AD")
        textAlign = Paint.Align.CENTER
    }

    fun setPattern(dots: List<Int>) {
        selectedDots.clear()
        dots.filter { it in 1..9 }.forEach { d ->
            if (!selectedDots.contains(d)) selectedDots.add(d)
        }
        isDragging = false
        invalidate()
    }

    fun clearPattern() {
        selectedDots.clear()
        isDragging = false
        currentTouchX = -1f
        currentTouchY = -1f
        invalidate()
        onPatternChanged?.invoke(emptyList())
    }

    fun getPatternDots(): List<Int> = selectedDots.toList()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val density = resources.displayMetrics.density
        val desiredSize = (220f * density).toInt()
        val width = resolveSize(desiredSize, widthMeasureSpec)
        val height = resolveSize(min(width, desiredSize), heightMeasureSpec)
        val size = min(width, height).coerceAtLeast((180f * density).toInt())
        setMeasuredDimension(size, size)
    }

    private fun getDotCenter(dotNumber: Int): Pair<Float, Float> {
        val idx = (dotNumber - 1).coerceIn(0, 8)
        val row = idx / 3
        val col = idx % 3
        val pad = width * 0.14f
        val usableW = (width - 2f * pad).coerceAtLeast(1f)
        val usableH = (height - 2f * pad).coerceAtLeast(1f)
        val stepX = usableW / 2f
        val stepY = usableH / 2f
        return Pair(pad + col * stepX, pad + row * stepY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val corner = 24f
        bgRect.set(2f, 2f, width - 2f, height - 2f)
        canvas.drawRoundRect(bgRect, corner, corner, bgPaint)
        canvas.drawRoundRect(bgRect, corner, corner, borderPaint)

        // Draw connecting lines first
        if (selectedDots.isNotEmpty()) {
            linePath.reset()
            val first = getDotCenter(selectedDots[0])
            linePath.moveTo(first.first, first.second)
            for (i in 1 until selectedDots.size) {
                val pt = getDotCenter(selectedDots[i])
                linePath.lineTo(pt.first, pt.second)
            }
            if (isDragging && currentTouchX >= 0f && currentTouchY >= 0f) {
                linePath.lineTo(currentTouchX, currentTouchY)
            }
            canvas.drawPath(linePath, linePaint)
        }

        val cellRadius = min(width, height) * 0.045f
        val activeRingRadius = cellRadius * 2.1f
        dotLabelPaint.textSize = cellRadius * 1.15f

        for (dot in 1..9) {
            val (cx, cy) = getDotCenter(dot)
            if (selectedDots.contains(dot)) {
                canvas.drawCircle(cx, cy, activeRingRadius, dotActiveRingPaint)
                canvas.drawCircle(cx, cy, cellRadius * 1.15f, dotActiveCorePaint)
            } else {
                canvas.drawCircle(cx, cy, cellRadius, dotIdlePaint)
            }
            canvas.drawText(dot.toString(), cx, cy + activeRingRadius + dotLabelPaint.textSize * 0.9f, dotLabelPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectedDots.clear()
                isDragging = true
                currentTouchX = event.x
                currentTouchY = event.y
                checkHitDot(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentTouchX = event.x
                currentTouchY = event.y
                checkHitDot(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                isDragging = false
                currentTouchX = -1f
                currentTouchY = -1f
                invalidate()
                onPatternChanged?.invoke(selectedDots.toList())
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun checkHitDot(x: Float, y: Float) {
        val hitRadius = min(width, height) * 0.125f
        for (dot in 1..9) {
            if (selectedDots.contains(dot)) continue
            val (cx, cy) = getDotCenter(dot)
            if (hypot((x - cx).toDouble(), (y - cy).toDouble()) <= hitRadius) {
                // Insert unvisited middle dot if jumping linearly or diagonally (e.g. 1->3 adds 2)
                if (selectedDots.isNotEmpty()) {
                    val lastIdx = selectedDots.last() - 1
                    val currIdx = dot - 1
                    val r1 = lastIdx / 3
                    val c1 = lastIdx % 3
                    val r2 = currIdx / 3
                    val c2 = currIdx % 3
                    if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) {
                        val midDot = ((r1 + r2) / 2) * 3 + ((c1 + c2) / 2) + 1
                        if (midDot != dot && !selectedDots.contains(midDot)) {
                            selectedDots.add(midDot)
                        }
                    }
                }
                selectedDots.add(dot)
                onPatternChanged?.invoke(selectedDots.toList())
                break
            }
        }
    }
}
