package me.bipul.videoeditor

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.bipul.videoeditor.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private var videoDuration = 0L
    private var tempInputFile: File? = null

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
                    Log.i(TAG, selectedVideoUri.toString())
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
                Toast.makeText(
                    this,
                    "Storage permission is required to save videos",
                    Toast.LENGTH_LONG
                ).show()
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
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clean up temporary files
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
        mBinding.videoView.setVideoURI(uri)
        mBinding.videoView.setOnPreparedListener { mp ->
            videoDuration = mp.duration.toLong()
            mBinding.seekBarStart.max = videoDuration.toInt()
            mBinding.seekBarEnd.max = videoDuration.toInt()
            mBinding.seekBarEnd.progress = videoDuration.toInt()
            mBinding.videoView.start()
        }
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

        // Use coroutine scope with IO dispatcher for file operations
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val inputPath = getRealPathFromURI(videoUri) ?: throw Exception("Cannot process video")
                val startUs = mBinding.seekBarStart.progress.toLong() * 1000
                val endUs = mBinding.seekBarEnd.progress.toLong() * 1000

                // Validate duration
                if (endUs <= startUs) {
                    throw IllegalArgumentException("Invalid trim duration")
                }

                val outputFile = createOutputFile() ?: throw Exception("Cannot create output file")

                val trimmer = VideoTrimmerHelper()
                trimmer.trimVideo(inputPath, outputFile.absolutePath, startUs, endUs, object : VideoTrimmerHelper.TrimCallback {
                    override fun onProgress(progress: Float) {
                        Log.d(TAG, "Trimming progress: ${progress * 100}%")
                    }

                    override fun onSuccess(outputFile: File) {
                        runOnUiThread {
                            addVideoToGallery(outputFile)
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
                    Toast.makeText(
                        this@MainActivity,
                        "Error: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e(TAG, "Error trimming video", e)
                }
            }
        }
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        return try {
            // Always use copy method for consistency
            tempInputFile?.delete() // Clean up previous temp file
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
                File(
                    getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                    "TrimmedVideos"
                )
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
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
                }
            }

            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(videoFile)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add video to gallery", e)
        }
    }
}