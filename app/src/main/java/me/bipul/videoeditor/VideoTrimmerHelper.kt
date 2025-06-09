package me.bipul.videoeditor

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class VideoTrimmerHelper {
    companion object {
        private const val TAG = "VideoTrimmerHelper"
        private const val BUFFER_SIZE = 1024 * 1024 // 1MB buffer
    }

    interface TrimCallback {
        fun onProgress(progress: Float)
        fun onSuccess(outputFile: File)
        fun onError(errorMessage: String)
    }

    suspend fun trimVideo(
        inputPath: String,
        outputPath: String,
        startUs: Long,
        endUs: Long,
        callback: TrimCallback
    ) {
        withContext(Dispatchers.IO) {
            var extractor: MediaExtractor? = null
            var muxer: MediaMuxer? = null

            try {
                Log.d(TAG, "Starting video trim: $inputPath -> $outputPath")
                Log.d(TAG, "Trim range: ${startUs / 1000}ms to ${endUs / 1000}ms")

                // Validate input file
                val inputFile = File(inputPath)
                if (!inputFile.exists() || !inputFile.canRead()) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Input file is invalid or inaccessible: $inputPath")
                    }
                    return@withContext
                }

                // Validate trim parameters
                if (startUs < 0 || endUs <= startUs) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Invalid trim parameters: startUs=${startUs / 1000}ms, endUs=${endUs / 1000}ms")
                    }
                    return@withContext
                }

                // Initialize extractor
                extractor = MediaExtractor().apply {
                    setDataSource(inputPath)
                    Log.d(TAG, "Extractor initialized with ${trackCount} tracks")
                }

                // Find tracks
                var videoTrackIndex = -1
                var audioTrackIndex = -1
                var videoFormat: MediaFormat? = null
                var audioFormat: MediaFormat? = null

                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                    Log.d(TAG, "Track $i: $mime")

                    when {
                        mime.startsWith("video/") && videoTrackIndex == -1 -> {
                            videoTrackIndex = i
                            videoFormat = format
                            Log.d(TAG, "Found video track at index $i")
                        }

                        mime.startsWith("audio/") && audioTrackIndex == -1 -> {
                            audioTrackIndex = i
                            audioFormat = format
                            Log.d(TAG, "Found audio track at index $i")
                        }
                    }
                }

                if (videoTrackIndex == -1 || videoFormat == null) {
                    withContext(Dispatchers.Main) {
                        callback.onError("No video track found in input file")
                    }
                    return@withContext
                }

                // Check video duration
                val videoDuration = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    videoFormat.getLong(MediaFormat.KEY_DURATION)
                } else {
                    // Try to get duration from file metadata
                    extractor.selectTrack(videoTrackIndex)
                    var maxTime = 0L
                    while (extractor.advance()) {
                        maxTime = extractor.sampleTime
                    }
                    maxTime
                }

                Log.d(TAG, "Video duration: ${videoDuration / 1000}ms")

                if (endUs > videoDuration && videoDuration > 0) {
                    withContext(Dispatchers.Main) {
                        callback.onError("End time (${endUs / 1000}ms) exceeds video duration (${videoDuration / 1000}ms)")
                    }
                    return@withContext
                }

                // Create output directory if needed
                val outputFile = File(outputPath)
                outputFile.parentFile?.let { parentDir ->
                    if (!parentDir.exists()) {
                        parentDir.mkdirs()
                    }
                }

                // Initialize muxer
                muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

                // Add video track
                extractor.selectTrack(videoTrackIndex)
                val videoOutputTrack = muxer.addTrack(videoFormat)
                Log.d(TAG, "Added video track to muxer")

                // Add audio track if exists
                var audioOutputTrack = -1
                if (audioTrackIndex != -1 && audioFormat != null) {
                    audioOutputTrack = muxer.addTrack(audioFormat)
                    Log.d(TAG, "Added audio track to muxer")
                }

                muxer.start()
                Log.d(TAG, "Muxer started")

                // Process video track
                val videoSuccess = processTrack(
                    extractor, videoTrackIndex, muxer, videoOutputTrack,
                    startUs, endUs, "video", callback
                )

                if (!videoSuccess) {
                    withContext(Dispatchers.Main) {
                        callback.onError("Failed to process video track")
                    }
                    return@withContext
                }

                // Process audio track if exists
                if (audioTrackIndex != -1) {
                    val audioSuccess = processTrack(
                        extractor, audioTrackIndex, muxer, audioOutputTrack,
                        startUs, endUs, "audio", callback
                    )

                    if (!audioSuccess) {
                        Log.w(TAG, "Audio processing failed, continuing with video only")
                    }
                }

                Log.d(TAG, "Video trimming completed successfully")
                withContext(Dispatchers.Main) {
                    callback.onSuccess(File(outputPath))
                }

            } catch (e: IOException) {
                Log.e(TAG, "IO error during video trimming", e)
                withContext(Dispatchers.Main) {
                    callback.onError("IO error: ${e.message}")
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "IllegalState error during video trimming", e)
                withContext(Dispatchers.Main) {
                    callback.onError("State error: ${e.message}")
                }
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "IllegalArgument error during video trimming", e)
                withContext(Dispatchers.Main) {
                    callback.onError("Argument error: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during video trimming", e)
                withContext(Dispatchers.Main) {
                    callback.onError("Unexpected error: ${e.javaClass.simpleName} - ${e.message}")
                }
            } finally {
                // Clean up resources
                try {
                    muxer?.stop()
                    muxer?.release()
                    extractor?.release()
                    Log.d(TAG, "Resources cleaned up")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during cleanup", e)
                }
            }
        }
    }

    private fun processTrack(
        extractor: MediaExtractor,
        trackIndex: Int,
        muxer: MediaMuxer,
        outputTrackIndex: Int,
        startUs: Long,
        endUs: Long,
        trackType: String,
        callback: TrimCallback
    ): Boolean {
        return try {
            Log.d(TAG, "Processing $trackType track")

            extractor.selectTrack(trackIndex)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val buffer = ByteBuffer.allocate(BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()
            var sampleCount = 0
            var lastProgressTime = 0L

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)


                if (sampleSize < 0) {
                    bufferInfo.set(0, 0, -1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                    Log.d(TAG, "$trackType track reached end of stream")
                    break
                }

                val sampleTime = extractor.sampleTime
                if (sampleTime < 0) break
                if (sampleTime > endUs) {
                    Log.d(
                        TAG,
                        "$trackType track reached end time: ${sampleTime / 1000}ms > ${endUs / 1000}ms"
                    )
                    break
                }


                if (sampleTime >= startUs) {
                    val extractorFlags = extractor.sampleFlags
                    var codecFlags = 0

                    // Convert MediaExtractor flags to MediaCodec flags
                    if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_SYNC_FRAME
                    }

                    // Handle partial frame flag for API 29+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
                        }
                    }

                    bufferInfo.set(
                        0,
                        sampleSize,
                        sampleTime - startUs,
                        codecFlags  // Use converted flags
                    )

                    muxer.writeSampleData(outputTrackIndex, buffer, bufferInfo)
                    sampleCount++

                    // Report progress periodically (every 100ms for video)
                    if (trackType == "video" && sampleTime - lastProgressTime > 100_000) {
                        val progress =
                            ((sampleTime - startUs).toFloat() / (endUs - startUs)).coerceIn(0f, 1f)
                        callback.onProgress(progress)
                        lastProgressTime = sampleTime
                    }
                }

                if (!extractor.advance()) {
                    Log.d(TAG, "$trackType track advance returned false")
                    break
                }
            }

            Log.d(TAG, "Processed $sampleCount samples for $trackType track")
            true

        } catch (e: Exception) {
            Log.e(TAG, "Error processing $trackType track", e)
            false
        }
    }
}