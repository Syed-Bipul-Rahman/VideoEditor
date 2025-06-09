package me.bipul.videoeditor

import android.graphics.Bitmap
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
        } else {
            holder.imageView.setImageResource(R.drawable.ic_video_placeholder) // Add a placeholder drawable
        }

        holder.itemView.setOnClickListener {
            onFrameClick(position)
        }
    }

    override fun getItemCount(): Int = frames.size
}