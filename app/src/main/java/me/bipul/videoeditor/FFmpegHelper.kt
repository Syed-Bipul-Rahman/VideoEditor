package me.bipul.videoeditor

import android.util.Log
import org.bytedeco.javacpp.Loader
import org.bytedeco.ffmpeg.global.avformat
import org.bytedeco.ffmpeg.global.avutil
import java.io.File
import java.io.IOException

object FFmpegHelper {

    private const val TAG = "FFmpegHelper"
    const val RETURN_CODE_SUCCESS = 0

    init {
        try {
            // Load FFmpeg libraries
            Loader.load(org.bytedeco.ffmpeg.global.avutil::class.java)
            Loader.load(org.bytedeco.ffmpeg.global.avcodec::class.java)
            Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
            avutil.av_log_set_level(avutil.AV_LOG_QUIET) // Reduce log verbosity
            Log.i(TAG, "FFmpeg libraries loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load FFmpeg libraries", e)
            throw RuntimeException("Failed to initialize FFmpeg", e)
        }
    }

    /**
     * Trims a video using FFmpeg, starting at the specified time and continuing for the given duration.
     *
     * @param inputPath Path to the input video file.
     * @param outputPath Path where the trimmed video will be saved.
     * @param startTime Start time for trimming, in seconds.
     * @param duration Duration of the trimmed segment, in seconds.
     * @throws IllegalArgumentException If input parameters are invalid.
     * @throws RuntimeException If FFmpeg execution fails.
     */
    fun trimVideo(
        inputPath: String,
        outputPath: String,
        startTime: Double,
        duration: Double
    ) {
        require(inputPath.isNotBlank()) { "Input path must not be blank" }
        require(outputPath.isNotBlank()) { "Output path must not be blank" }
        require(startTime >= 0) { "Start time must be non-negative" }
        require(duration > 0) { "Duration must be positive" }

        val inputFile = File(inputPath)
        require(inputFile.exists() && inputFile.canRead()) { "Input file does not exist or is not readable: $inputPath" }

        val outputFile = File(outputPath)
        val outputDir = outputFile.parentFile
        if (outputDir != null && !outputDir.exists()) {
            outputDir.mkdirs()
        }

        Log.i(TAG, "Starting video trim: $inputPath -> $outputPath")
        Log.i(TAG, "Start time: ${startTime}s, Duration: ${duration}s")

        try {
            val result = executeFFmpegCommand(inputPath, outputPath, startTime, duration)
            if (result != RETURN_CODE_SUCCESS) {
                throw RuntimeException("FFmpeg failed with exit code $result")
            }

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
        }
    }

    private fun executeFFmpegCommand(
        inputPath: String,
        outputPath: String,
        startTime: Double,
        duration: Double
    ): Int {
        return try {
            // Using JavaCV's FFmpeg binding for more reliable execution
            val command = buildFFmpegCommand(inputPath, outputPath, startTime, duration)
            Log.d(TAG, "FFmpeg command: ${command.joinToString(" ")}")

            val processBuilder = ProcessBuilder(command)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            // Read output for debugging
            val output = process.inputStream.bufferedReader().readText()
            if (output.isNotEmpty()) {
                Log.d(TAG, "FFmpeg output: $output")
            }

            val exitCode = process.waitFor()
            Log.i(TAG, "FFmpeg process completed with exit code: $exitCode")

            exitCode
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start FFmpeg process", e)
            throw RuntimeException("Failed to start FFmpeg process", e)
        } catch (e: InterruptedException) {
            Log.e(TAG, "FFmpeg process interrupted", e)
            throw RuntimeException("FFmpeg process interrupted", e)
        }
    }

    private fun buildFFmpegCommand(
        inputPath: String,
        outputPath: String,
        startTime: Double,
        duration: Double
    ): List<String> {
        // Get the FFmpeg binary path
        val ffmpegPath = try {
            Loader.load(org.bytedeco.ffmpeg.global.avutil::class.java)
        } catch (e: Exception) {
            "ffmpeg" // Fallback to system FFmpeg
        }

        return listOf(
            ffmpegPath.toString(),
            "-y", // Overwrite output file
            "-ss", startTime.toString(), // Start time
            "-i", inputPath, // Input file
            "-t", duration.toString(), // Duration
            "-c", "copy", // Copy streams without re-encoding
            "-avoid_negative_ts", "make_zero", // Handle negative timestamps
            outputPath // Output file
        )
    }

    /**
     * Alternative implementation using direct FFmpeg library calls
     * Use this if the process-based approach doesn't work
     */
    fun trimVideoDirectly(
        inputPath: String,
        outputPath: String,
        startTime: Double,
        duration: Double
    ) {
        require(inputPath.isNotBlank()) { "Input path must not be blank" }
        require(outputPath.isNotBlank()) { "Output path must not be blank" }
        require(startTime >= 0) { "Start time must be non-negative" }
        require(duration > 0) { "Duration must be positive" }

        Log.i(TAG, "Using direct FFmpeg library calls for trimming")

        // This is a simplified version - you might need to implement more complex
        // logic based on your specific FFmpeg library binding
        try {
            // Initialize FFmpeg
            avformat.avformat_network_init()

            // Your direct FFmpeg implementation would go here
            // This is a placeholder for the actual implementation
            throw UnsupportedOperationException("Direct FFmpeg implementation not yet implemented. Use trimVideo() instead.")

        } catch (e: Exception) {
            Log.e(TAG, "Direct FFmpeg trimming failed", e)
            throw RuntimeException("Direct FFmpeg trimming failed", e)
        }
    }
}