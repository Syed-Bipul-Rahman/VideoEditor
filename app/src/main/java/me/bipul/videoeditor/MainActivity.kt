package me.bipul.videoeditor

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bipul.videoeditor.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private var videoDuration = 0L
    private var tempInputFile: File? = null
    private var exoPlayer: ExoPlayer? = null
    private var startTrimMs: Long = 0L
    private var endTrimMs: Long = 0L

    companion object {
        const val TAG = "VideoEditor"
        const val STORAGE_PERMISSION_CODE = 1001
    }

    private val videoPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.data?.also { uri ->
                    selectedVideoUri = uri
                    setupVideoPreview(uri)
                    Log.i(TAG, "Selected video URI: $uri")
                }
            }
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                selectedVideoUri?.let { uri ->
                    trimVideo(uri)
                }
            } else {
                Toast.makeText(this, "Storage permission required", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)

        mBinding.btnImport.setOnClickListener {
            pickVideo()
        }

        mBinding.btnExport.setOnClickListener {
            selectedVideoUri?.let { uri ->
                if (checkAndRequestPermissions()) {
                    trimVideo(uri)
                }
            } ?: Toast.makeText(this, "Please import a video first", Toast.LENGTH_SHORT).show()
        }

        // Setup VideoTimelineView callbacks
        mBinding.timelineView.onFrameClick = { frameIndex ->
            val frameCount = mBinding.timelineView.getFrameCount()
            val timestampMs = if (frameCount > 0) (frameIndex * videoDuration) / frameCount else 0L
            exoPlayer?.seekTo(timestampMs)
            Log.d(TAG, "Frame clicked at index $frameIndex, seeking to $timestampMs ms")
        }

        mBinding.timelineView.onSelectionChanged = { startRatio, endRatio ->
            startTrimMs = (startRatio * videoDuration).toLong()
            endTrimMs = (endRatio * videoDuration).toLong()
            exoPlayer?.seekTo(startTrimMs)
            Log.d(TAG, "Trim selection changed: $startTrimMs to $endTrimMs ms")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tempInputFile?.delete()
        exoPlayer?.release()
        cacheDir.listFiles()?.filter { it.name.startsWith("temp_output_") }?.forEach { it.delete() }
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        videoPickerLauncher.launch(intent)
    }

    private fun setupVideoPreview(uri: Uri) {
        exoPlayer?.release()
        exoPlayer = ExoPlayer.Builder(this).build()
        mBinding.playerView.player = exoPlayer
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)

        exoPlayer?.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playbackState == com.google.android.exoplayer2.Player.STATE_READY) {
                    val duration = exoPlayer?.duration?.toLong() ?: Long.MIN_VALUE
                    Log.d(TAG, "ExoPlayer duration: $duration ms")

                    if (duration == Long.MIN_VALUE || duration <= 0) {
                        Log.w(TAG, "Invalid ExoPlayer duration, trying alternative method")
                        // Try to get duration directly from MediaMetadataRetriever first
                        getDurationFromMetadataRetriever(uri) { retrievedDuration ->
                            if (retrievedDuration > 0) {
                                videoDuration = retrievedDuration
                                extractFrames(uri)
                            } else {
                                // Fallback to polling
                                CoroutineScope(Dispatchers.Main).launch {
                                    fetchValidDuration(uri)
                                }
                            }
                        }
                    } else {
                        videoDuration = duration
                        extractFrames(uri)
                    }
                }
            }

            override fun onTimelineChanged(timeline: com.google.android.exoplayer2.Timeline, reason: Int) {
                val newDuration = exoPlayer?.duration?.toLong() ?: Long.MIN_VALUE
                Log.d(TAG, "Timeline changed, duration: $newDuration ms")
                if (newDuration > 0 && videoDuration != newDuration) {
                    videoDuration = newDuration
                    extractFrames(uri)
                }
            }
        })

        mBinding.playerView.setOnTouchListener { _, _ -> true }
        exoPlayer?.prepare()

        // Don't auto-play to avoid issues
        exoPlayer?.playWhenReady = false
    }

    private fun getDurationFromMetadataRetriever(uri: Uri, callback: (Long) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val retriever = MediaMetadataRetriever()
            try {
                // First try to copy to temp file for more reliable access
                val tempPath = getRealPathFromURI(uri)
                if (tempPath != null) {
                    retriever.setDataSource(tempPath)
                } else {
                    // Fallback to direct URI access
                    contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        retriever.setDataSource(pfd.fileDescriptor)
                    } ?: throw Exception("Cannot open video file")
                }

                val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val duration = durationStr?.toLongOrNull() ?: 0L
                Log.d(TAG, "MediaMetadataRetriever duration: $duration ms")

                withContext(Dispatchers.Main) {
                    callback(duration)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get duration from MediaMetadataRetriever", e)
                withContext(Dispatchers.Main) {
                    callback(0L)
                }
            } finally {
                try {
                    retriever.release()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to release MediaMetadataRetriever", e)
                }
            }
        }
    }

    private fun extractFrames(uri: Uri) {
        Log.d(TAG, "Starting frame extraction for duration: $videoDuration ms")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputPath = getRealPathFromURI(uri)
                if (inputPath == null) {
                    Log.e(TAG, "Failed to get real path from URI: $uri")
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "Failed to access video file", Toast.LENGTH_LONG).show()
                        // Show placeholder frames
                        val dummyFrames = List(10) { null }
                        mBinding.timelineView.setFrames(dummyFrames)
                        mBinding.timelineView.setSelection(0f, 1f)
                    }
                    return@launch
                }

                Log.d(TAG, "Extracting frames from: $inputPath")
                val frameExtractor = VideoFrameExtractor()
                val frames = frameExtractor.extractFrames(inputPath, frameCount = 10)

                withContext(Dispatchers.Main) {
                    Log.d(TAG, "Extracted ${frames.size} frames, null frames: ${frames.count { it == null }}")

                    if (frames.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Failed to extract video frames - empty list", Toast.LENGTH_LONG).show()
                        val dummyFrames = List(10) { null }
                        mBinding.timelineView.setFrames(dummyFrames)
                    } else if (frames.all { it == null }) {
                        Toast.makeText(this@MainActivity, "Failed to extract video frames - all null", Toast.LENGTH_LONG).show()
                        mBinding.timelineView.setFrames(frames) // Still set them to show placeholders
                    } else {
                        Log.d(TAG, "Successfully extracted ${frames.count { it != null }} valid frames")
                        mBinding.timelineView.setFrames(frames)
                    }

                    mBinding.timelineView.setSelection(0f, 1f)
                    startTrimMs = 0L
                    endTrimMs = videoDuration
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in extractFrames", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error extracting frames: ${e.message}", Toast.LENGTH_LONG).show()
                    val dummyFrames = List(10) { null }
                    mBinding.timelineView.setFrames(dummyFrames)
                    mBinding.timelineView.setSelection(0f, 1f)
                }
            }
        }
    }

    private suspend fun fetchValidDuration(uri: Uri) {
        // Poll ExoPlayer duration for up to 5 seconds
        var attempts = 0
        val maxAttempts = 50
        val delayMs = 100L

        while (attempts < maxAttempts) {
            val duration = exoPlayer?.duration?.toLong() ?: Long.MIN_VALUE
            Log.d(TAG, "Polling duration, attempt $attempts: $duration ms")
            if (duration > 0) {
                videoDuration = duration
                extractFrames(uri)
                return
            }
            delay(delayMs)
            attempts++
        }

        // If polling failed, show error but still try to extract frames
        Log.w(TAG, "ExoPlayer duration polling failed completely")
        withContext(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Unable to determine video duration", Toast.LENGTH_LONG).show()
            // Try to extract with a default duration or show placeholders
            val dummyFrames = List(10) { null }
            mBinding.timelineView.setFrames(dummyFrames)
            mBinding.timelineView.setSelection(0f, 1f)
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            true // No need for runtime permissions for MediaStore access in Android 13+
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            true // For Android 10-12, READ_MEDIA_VIDEO is sufficient for picking videos
        } else {
            val writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val readPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            if (writePermission != PackageManager.PERMISSION_GRANTED || readPermission != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    )
                )
                false
            } else {
                true
            }
        }
    }

    private fun trimVideo(videoUri: Uri) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Processing Video")
            .setMessage("Trimming video, please wait...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputPath = getRealPathFromURI(videoUri) ?: throw Exception("Cannot process video")
                if (endTrimMs <= startTrimMs) {
                    throw Exception("Invalid trim duration")
                }

                val outputUri = createOutputUri() ?: throw Exception("Cannot create output file")
                val outputPath = getRealPathFromUriOrFile(outputUri)
                    ?: throw Exception("Invalid output path")

                val trimmer = VideoTrimmerHelper()
                trimmer.trimVideo(
                    inputPath,
                    outputPath,
                    startTrimMs * 1000, // Convert ms to µs
                    endTrimMs * 1000,   // Convert ms to µs
                    object : VideoTrimmerHelper.TrimCallback {
                        override fun onProgress(progress: Float) {
                            Log.d(TAG, "Trimming progress: ${progress * 100}%")
                        }

                        override fun onSuccess(outputFile: File) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                if (copyFileToMediaStore(outputPath, outputUri)) {
                                    runOnUiThread {
                                        addVideoToGallery(outputUri)
                                        progressDialog.dismiss()
                                        Toast.makeText(this@MainActivity, "Video trimmed successfully!", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    runOnUiThread {
                                        progressDialog.dismiss()
                                        Toast.makeText(this@MainActivity, "Error: Failed to save video", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                runOnUiThread {
                                    addVideoToGallery(outputUri)
                                    progressDialog.dismiss()
                                    Toast.makeText(this@MainActivity, "Video trimmed successfully!", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }

                        override fun onError(errorMessage: String) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                Toast.makeText(this@MainActivity, "Error: $errorMessage", Toast.LENGTH_LONG).show()
                                Log.e(TAG, errorMessage)
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Error trimming video", e)
                }
            }
        }
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        return try {
            tempInputFile?.delete()
            val tempFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")
            tempInputFile = tempFile
            Log.d(TAG, "Copying URI $uri to temp file: ${tempFile.absolutePath}")

            contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    val bytesCopied = inputStream.copyTo(outputStream)
                    Log.d(TAG, "Copied $bytesCopied bytes to temp file")
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream for URI: $uri")
                return null
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                Log.d(TAG, "Temp file created: ${tempFile.absolutePath}, size: ${tempFile.length()} bytes")
                tempFile.absolutePath
            } else {
                Log.e(TAG, "Temp file empty or not created: exists=${tempFile.exists()}, size=${tempFile.length()}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to file: $uri", e)
            null
        }
    }

    private fun createOutputUri(): Uri? {
        return try {
            val fileName = "trimmed_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/VideoEditor")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                } else {
                    val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val videoDir = File(moviesDir, "VideoEditor")
                    videoDir.mkdirs()
                    put(MediaStore.Video.Media.DATA, File(videoDir, fileName).absolutePath)
                }
            }

            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Failed to create MediaStore entry")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create output file", e)
            null
        }
    }

    private fun getRealPathFromUriOrFile(uri: Uri): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val tempFile = File(cacheDir, "temp_output_${System.currentTimeMillis()}.mp4")
                tempFile.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create temp output file", e)
                null
            }
        } else {
            contentResolver.query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                } else {
                    null
                }
            }
        }
    }

    private fun copyFileToMediaStore(tempFilePath: String, mediaStoreUri: Uri): Boolean {
        return try {
            val tempFile = File(tempFilePath)
            if (!tempFile.exists()) {
                Log.e(TAG, "Temp file doesn't exist: $tempFilePath")
                return false
            }

            contentResolver.openOutputStream(mediaStoreUri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            tempFile.delete()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file to MediaStore", e)
            false
        }
    }

    private fun addVideoToGallery(uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, values, null, null)
            } else {
                sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri))
            }
            Log.d(TAG, "Video added to gallery: $uri")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add video to gallery", e)
        }
    }
}