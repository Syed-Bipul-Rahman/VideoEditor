package me.bipul.videoeditor

import android.graphics.Bitmap
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView

class FrameAdapter(
    private val frames: List<Bitmap?>,
    private val onFrameClick: (Int) -> Unit
) : RecyclerView.Adapter<FrameAdapter.FrameViewHolder>() {

    class FrameViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val imageView: ImageView = view.findViewById(R.id.frame_image)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FrameViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_frame, parent, false)
        return FrameViewHolder(view)
    }

    override fun onBindViewHolder(holder: FrameViewHolder, position: Int) {
        val frame = frames[position]
        if (frame != null) {
            holder.imageView.setImageBitmap(frame)
            Log.d("FrameAdapter", "Bound frame at position $position")
        } else {
            holder.imageView.setImageResource(R.drawable.ic_video_placeholder)
            Log.d("FrameAdapter", "Bound placeholder at position $position")
        }

        holder.itemView.setOnClickListener {
            onFrameClick(position)
        }
    }

    override fun getItemCount(): Int = frames.size
}