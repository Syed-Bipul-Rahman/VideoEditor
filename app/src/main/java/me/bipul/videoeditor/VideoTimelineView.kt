package me.bipul.videoeditor

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.bipul.videoeditor.FrameAdapter
import me.bipul.videoeditor.TimelineSelectionOverlay

class VideoTimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val recyclerView: RecyclerView
    private val selectionOverlay: TimelineSelectionOverlay

    var onFrameClick: ((frameIndex: Int) -> Unit)? = null
    var onSelectionChanged: ((startRatio: Float, endRatio: Float) -> Unit)? = null

    init {
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        selectionOverlay = TimelineSelectionOverlay(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            onSelectionChanged = { startRatio, endRatio ->
                this@VideoTimelineView.onSelectionChanged?.invoke(startRatio, endRatio)
            }
        }
        addView(recyclerView)
        addView(selectionOverlay)
    }

    fun setFrames(frames: List<android.graphics.Bitmap?>) {
        val adapter = FrameAdapter(frames) { frameIndex ->
            onFrameClick?.invoke(frameIndex)
        }
        recyclerView.adapter = adapter
        Log.d("VideoTimelineView", "Set adapter with ${frames.size} frames")
    }

    fun setSelection(startRatio: Float, endRatio: Float) {
        selectionOverlay.setSelection(startRatio, endRatio)
    }

    fun getFrameCount(): Int {
        return (recyclerView.adapter as? FrameAdapter)?.itemCount ?: 0
    }
}