package me.bipul.videoeditor

import android.util.Log
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacpp.Pointer
import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.avutil.*
import org.bytedeco.ffmpeg.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.avcodec.*
import java.io.File

object FFmpegHelper {

    private const val TAG = "FFmpegHelper"
    private var isInitialized = false

    init {
        try {
            // Load FFmpeg libraries in correct order
            Loader.load(org.bytedeco.ffmpeg.global.avcodec::class.java)
            Loader.load(org.bytedeco.ffmpeg.global.avutil::class.java)
            Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            Loader.load(org.bytedeco.ffmpeg.global.swscale::class.java)

            // Initialize FFmpeg
            avformat_network_init()
            av_log_set_level(AV_LOG_ERROR) // Reduce log verbosity

            isInitialized = true
            Log.i(TAG, "FFmpeg libraries loaded and initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load FFmpeg libraries", e)
            isInitialized = false
        }
    }

    fun isAvailable(): Boolean = isInitialized

    /**
     * Trims a video using FFmpeg native libraries
     */
    fun trimVideo(
        inputPath: String,
        outputPath: String,
        startTime: Double,
        duration: Double
    ) {
        if (!isInitialized) {
            throw RuntimeException("FFmpeg libraries not initialized")
        }

        require(inputPath.isNotBlank()) { "Input path must not be blank" }
        require(outputPath.isNotBlank()) { "Output path must not be blank" }
        require(startTime >= 0) { "Start time must be non-negative" }
        require(duration > 0) { "Duration must be positive" }

        val inputFile = File(inputPath)
        require(inputFile.exists() && inputFile.canRead()) {
            "Input file does not exist or is not readable: $inputPath"
        }

        val outputFile = File(outputPath)
        outputFile.parentFile?.let { parentDir ->
            if (!parentDir.exists()) {
                parentDir.mkdirs()
            }
        }

        Log.i(TAG, "Starting video trim: $inputPath -> $outputPath")
        Log.i(TAG, "Start time: ${startTime}s, Duration: ${duration}s")

        var inputFormatContext: AVFormatContext? = null
        var outputFormatContext: AVFormatContext? = null

        try {
            // Open input file
            inputFormatContext = avformat_alloc_context()
            if (avformat_open_input(inputFormatContext, inputPath, null, null) < 0) {
                throw RuntimeException("Could not open input file: $inputPath")
            }

            if (avformat_find_stream_info(inputFormatContext, null as AVDictionary?) < 0) {
                throw RuntimeException("Could not find stream information")
            }

            // Create output context
            outputFormatContext = AVFormatContext(null)
            if (avformat_alloc_output_context2(outputFormatContext, null, null, outputPath) < 0) {
                throw RuntimeException("Could not create output context")
            }

            val outputFormat = outputFormatContext.oformat()

            // Copy streams from input to output
            val streamMapping = IntArray(inputFormatContext.nb_streams())
            var outputStreamIndex = 0

            for (i in 0 until inputFormatContext.nb_streams()) {
                val inputStream = inputFormatContext.streams(i)
                val inputCodecParams = inputStream.codecpar()

                if (inputCodecParams.codec_type() != AVMEDIA_TYPE_AUDIO &&
                    inputCodecParams.codec_type() != AVMEDIA_TYPE_VIDEO &&
                    inputCodecParams.codec_type() != AVMEDIA_TYPE_SUBTITLE) {
                    streamMapping[i] = -1
                    continue
                }

                streamMapping[i] = outputStreamIndex++

                val outputStream = avformat_new_stream(outputFormatContext, null)
                if (outputStream == null) {
                    throw RuntimeException("Failed to allocate output stream")
                }

                if (avcodec_parameters_copy(outputStream.codecpar(), inputCodecParams) < 0) {
                    throw RuntimeException("Failed to copy codec parameters")
                }

                outputStream.codecpar().codec_tag(0)
            }

            // Open output file
            if ((outputFormat.flags() and AVFMT_NOFILE) == 0) {
                if (avio_open(outputFormatContext.pb(), outputPath, AVIO_FLAG_WRITE) < 0) {
                    throw RuntimeException("Could not open output file: $outputPath")
                }
            }

            // Write header
            if (avformat_write_header(outputFormatContext, null as AVDictionary?) < 0) {
                throw RuntimeException("Error occurred when opening output file")
            }

            // Seek to start time
            val startTimeUs = (startTime * AV_TIME_BASE).toLong()
            if (av_seek_frame(inputFormatContext, -1, startTimeUs, AVSEEK_FLAG_BACKWARD) < 0) {
                Log.w(TAG, "Could not seek to start time, starting from beginning")
            }

            // Calculate end time
            val endTimeUs = startTimeUs + (duration * AV_TIME_BASE).toLong()

            val packet = AVPacket()
            while (av_read_frame(inputFormatContext, packet) >= 0) {
                val inputStream = inputFormatContext.streams(packet.stream_index())

                if (packet.stream_index() >= streamMapping.size || streamMapping[packet.stream_index()] < 0) {
                    av_packet_unref(packet)
                    continue
                }

                // Check if we've reached the end time
                val packetTime = av_rescale_q(packet.pts(), inputStream.time_base(), av_get_time_base_q())
                if (packetTime > endTimeUs) {
                    av_packet_unref(packet)
                    break
                }

                packet.stream_index(streamMapping[packet.stream_index()])
                val outputStream = outputFormatContext.streams(packet.stream_index())

                // Convert packet timestamps
                av_packet_rescale_ts(packet, inputStream.time_base(), outputStream.time_base())

                if (av_interleaved_write_frame(outputFormatContext, packet) < 0) {
                    Log.e(TAG, "Error while writing packet")
                    break
                }

                av_packet_unref(packet)
            }

            // Write trailer
            av_write_trailer(outputFormatContext)

            // Verify output file was created
            if (!outputFile.exists() || outputFile.length() == 0L) {
                throw RuntimeException("Output file was not created or is empty")
            }

            Log.i(TAG, "Video trim completed successfully: ${outputFile.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error during video trimming", e)
            // Clean up partial output file
            if (outputFile.exists()) {
                outputFile.delete()
            }
            throw e
        } finally {
            // Clean up resources
            inputFormatContext?.let { ctx ->
                avformat_close_input(ctx)
            }

            outputFormatContext?.let { ctx ->
                if ((ctx.oformat().flags() and AVFMT_NOFILE) == 0) {
                    avio_closep(ctx.pb())
                }
                avformat_free_context(ctx)
            }
        }
    }
}