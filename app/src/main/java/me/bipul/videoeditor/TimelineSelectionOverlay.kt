package me.bipul.videoeditor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.toColorInt

class TimelineSelectionOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = "#80ADD8E6".toColorInt() // Light blue with transparency
        style = Paint.Style.FILL
    }

    private val handlePaint = Paint().apply {
        color = "#4169E1".toColorInt() // Royal blue for handles
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint().apply {
        color = "#4169E1".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val handleWidth = 20f
    private val handleHeight = 60f

    private var startPosition = 0f
    private var endPosition = 0f
    private var isDraggingStart = false
    private var isDraggingEnd = false
    private var lastTouchX = 0f

    var onSelectionChanged: ((startRatio: Float, endRatio: Float) -> Unit)? = null

    fun setSelection(startRatio: Float, endRatio: Float) {
        startPosition = startRatio * width
        endPosition = endRatio * width
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (endPosition == 0f) {
            endPosition = w.toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val height = height.toFloat()

        // Draw overlay on unselected areas
        // Left overlay
        if (startPosition > 0) {
            canvas.drawRect(0f, 0f, startPosition, height, overlayPaint)
        }

        // Right overlay
        if (endPosition < width) {
            canvas.drawRect(endPosition, 0f, width.toFloat(), height, overlayPaint)
        }

        // Draw selection border
        canvas.drawRect(startPosition, 0f, endPosition, height, borderPaint)

        // Draw handles
        val handleTop = (height - handleHeight) / 2
        val handleBottom = handleTop + handleHeight

        // Left handle
        val leftHandleRect = RectF(
            startPosition - handleWidth/2,
            handleTop,
            startPosition + handleWidth/2,
            handleBottom
        )
        canvas.drawRoundRect(leftHandleRect, 8f, 8f, handlePaint)

        // Right handle
        val rightHandleRect = RectF(
            endPosition - handleWidth/2,
            handleTop,
            endPosition + handleWidth/2,
            handleBottom
        )
        canvas.drawRoundRect(rightHandleRect, 8f, 8f, handlePaint)

        // Draw handle indicators (vertical lines)
        canvas.drawLine(startPosition, handleTop + 15, startPosition, handleBottom - 15, Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
        })
        canvas.drawLine(endPosition, handleTop + 15, endPosition, handleBottom - 15, Paint().apply {
            color = Color.WHITE
            strokeWidth = 2f
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x

                // Check if touching start handle
                if (abs(event.x - startPosition) <= handleWidth) {
                    isDraggingStart = true
                    return true
                }

                // Check if touching end handle
                if (abs(event.x - endPosition) <= handleWidth) {
                    isDraggingEnd = true
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = event.x - lastTouchX

                if (isDraggingStart) {
                    startPosition = max(0f, min(endPosition - handleWidth, startPosition + deltaX))
                    invalidate()
                    onSelectionChanged?.invoke(startPosition / width, endPosition / width)
                } else if (isDraggingEnd) {
                    endPosition = max(startPosition + handleWidth, min(width.toFloat(), endPosition + deltaX))
                    invalidate()
                    onSelectionChanged?.invoke(startPosition / width, endPosition / width)
                }

                lastTouchX = event.x
                return true
            }

            MotionEvent.ACTION_UP -> {
                isDraggingStart = false
                isDraggingEnd = false
                return true
            }
        }

        return super.onTouchEvent(event)
    }
}