package me.bipul.videoeditor

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bipul.videoeditor.databinding.ActivityMainBinding
import java.io.File
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.ui.PlayerView

class MainActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private var videoDuration = 0L
    private var tempInputFile: File? = null

    private var exoPlayer: ExoPlayer? = null

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
                    trimSelectedVideo(uri)
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
                    trimSelectedVideo(uri)
                }
            } ?: Toast.makeText(this, "Please import a video first", Toast.LENGTH_SHORT).show()
        }

        mBinding.seekBarStart.setOnSeekBarChangeListener(createSeekBarListener(isStart = true))
        mBinding.seekBarEnd.setOnSeekBarChangeListener(createSeekBarListener(isStart = false))
    }

    private fun createSeekBarListener(isStart: Boolean): SeekBar.OnSeekBarChangeListener {
        val handler = Handler(Looper.getMainLooper())
        return object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    Log.d(
                        TAG,
                        "SeekBar ${if (isStart) "Start" else "End"} progress: $progress, max: ${seekBar.max}"
                    )
                    if (isStart) {
                        if (progress > mBinding.seekBarEnd.progress) {
                            mBinding.seekBarEnd.progress = progress
                            Log.d(TAG, "Adjusted seekBarEnd to: $progress")
                        }
                        handler.post {
                            try {
                             //   mBinding.videoView.seekTo(progress)
                                Log.d(TAG, "Seek to $progress successful")
                            } catch (e: Exception) {
                                Log.e(TAG, "Error seeking VideoView to $progress", e)
                            }
                        }
                    } else {
                        if (progress < mBinding.seekBarStart.progress) {
                            mBinding.seekBarStart.progress = progress
                            Log.d(TAG, "Adjusted seekBarStart to: $progress")
                        }
                    }
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
          //      mBinding.videoView.pause()
                Log.d(TAG, "Started tracking ${if (isStart) "Start" else "End"} SeekBar")
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                Log.d(TAG, "Stopped tracking ${if (isStart) "Start" else "End"} SeekBar")
                if (isStart) {
                    handler.post {
                  //      mBinding.videoView.start()
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tempInputFile?.delete()
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        videoPickerLauncher.launch(intent)
    }


    private fun setupVideoPreview(uri: Uri) {
        exoPlayer = ExoPlayer.Builder(this).build()
        mBinding.playerView.player = exoPlayer // Use PlayerView in layout instead of VideoView
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.setMediaItem(mediaItem)
        exoPlayer?.addListener(object : com.google.android.exoplayer2.Player.Listener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playbackState == com.google.android.exoplayer2.Player.STATE_READY) {
                    videoDuration = exoPlayer?.duration?.toLong() ?: 0L
                    Log.d(TAG, "ExoPlayer duration: $videoDuration ms")
                    if (videoDuration <= 0) {
                        Toast.makeText(
                            this@MainActivity,
                            "Invalid video duration",
                            Toast.LENGTH_SHORT
                        ).show()
                        return
                    }
                    mBinding.seekBarStart.max = videoDuration.toInt()
                    mBinding.seekBarEnd.max = videoDuration.toInt()
                    mBinding.seekBarEnd.progress = videoDuration.toInt()
                    mBinding.seekBarStart.progress = 0
                    mBinding.seekBarStart.isEnabled = true
                    mBinding.seekBarEnd.isEnabled = true
                    mBinding.seekBarStart.isFocusable = true
                    mBinding.seekBarEnd.isFocusable = true
                    mBinding.seekBarStart.isFocusableInTouchMode = true
                    mBinding.seekBarEnd.isFocusableInTouchMode = true
                    mBinding.seekBarStart.isClickable = true
                    mBinding.seekBarEnd.isClickable = true
                    mBinding.seekBarStart.requestFocus()
                }
            }
        })
        mBinding.playerView.setOnTouchListener { _, _ -> true }
        exoPlayer?.prepare()
        exoPlayer?.play()
    }

    private fun checkAndRequestPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            val writePermission =
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val readPermission =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
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

    private fun trimSelectedVideo(videoUri: Uri) {
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Processing Video")
            .setMessage("Trimming video, please wait...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputPath =
                    getRealPathFromURI(videoUri) ?: throw Exception("Cannot process video")
                val startUs = mBinding.seekBarStart.progress.toLong() * 1000 // Convert ms to µs
                val endUs = mBinding.seekBarEnd.progress.toLong() * 1000 // Convert ms to µs
                if (endUs <= startUs) {
                    throw IllegalArgumentException("Invalid trim duration")
                }

                val outputFile = createOutputFile() ?: throw Exception("Cannot create output file")

                val trimmer = VideoTrimmerHelper()
                trimmer.trimVideo(
                    inputPath,
                    outputFile.absolutePath,
                    startUs,
                    endUs,
                    object : VideoTrimmerHelper.TrimCallback {
                        override fun onProgress(progress: Float) {
                            Log.d(TAG, "Trimming progress: ${progress * 100}%")
                        }

                        override fun onSuccess(outputFile: File) {
                            runOnUiThread {
                                addVideoToGallery(outputFile)

                                Log.w(TAG, "Trimming result: $outputFile")
                                progressDialog.dismiss()
                                Toast.makeText(
                                    this@MainActivity,
                                    "Video trimmed successfully!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                        override fun onError(errorMessage: String) {
                            runOnUiThread {
                                progressDialog.dismiss()
                                Toast.makeText(
                                    this@MainActivity,
                                    "Error: $errorMessage",
                                    Toast.LENGTH_LONG
                                ).show()
                                Log.e(TAG, errorMessage)
                            }
                        }
                    })
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG)
                        .show()
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

            contentResolver.openInputStream(uri)?.use { inputStream ->
                tempFile.outputStream().use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (tempFile.exists() && tempFile.length() > 0) {
                tempFile.absolutePath
            } else {
                Log.e(TAG, "Temporary file is empty or not created")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to file: $uri", e)
            null
        }
    }

    private fun createOutputFile(): File? {
        return try {
            val videoDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TrimmedVideos")
            } else {
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "VideoEditor"
                ).apply { mkdirs() }
            }
            videoDir.mkdirs()
            File(videoDir, "trimmed_${System.currentTimeMillis()}.mp4")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create output file", e)
            null
        }
    }

    private fun addVideoToGallery(videoFile: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATA, videoFile.absolutePath)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Video.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_MOVIES + "/VideoEditor"
                    )
                }
            }
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(videoFile)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add video to gallery", e)
        }
    }
}