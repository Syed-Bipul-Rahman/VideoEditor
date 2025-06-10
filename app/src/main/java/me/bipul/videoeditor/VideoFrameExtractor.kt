package me.bipul.videoeditor
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoFrameExtractor {
    companion object {
        const val TAG = "VideoFrameExtractor"
    }

    suspend fun extractFrames(videoPath: String, frameCount: Int): List<Bitmap?> = withContext(Dispatchers.IO) {
        val frames = mutableListOf<Bitmap?>()
        val retriever = MediaMetadataRetriever()

        try {
            retriever.setDataSource(videoPath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationStr?.toLongOrNull() ?: 0L

            if (duration <= 0) {
                Log.e(TAG, "Invalid video duration")
                return@withContext emptyList()
            }

            val interval = duration / frameCount

            for (i in 0 until frameCount) {
                val timeUs = i * interval * 1000 // Convert to microseconds
                try {
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frames.add(frame)
                    Log.d(TAG, "Extracted frame at ${i * interval}ms")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract frame at ${i * interval}ms", e)
                    frames.add(null)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract frames", e)
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to release MediaMetadataRetriever", e)
            }
        }

        frames
    }
}