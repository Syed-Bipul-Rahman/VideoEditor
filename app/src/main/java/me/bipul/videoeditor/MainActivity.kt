package me.bipul.videoeditor

import android.Manifest
import android.app.Activity
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.bipul.videoeditor.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityMainBinding
    private var selectedVideoUri: Uri? = null
    private var videoDuration = 0L

    companion object {
        const val TAG = "VideoEditor"
        const val STORAGE_PERMISSION_CODE = 1001
    }

    // Define the ActivityResultLauncher as a class property
    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.also { uri ->
                selectedVideoUri = uri
                setupVideoPreview(uri)
                Log.i(TAG, selectedVideoUri.toString())
            }
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            selectedVideoUri?.let { uri ->
                trimSelectedVideo(uri)
            }
        } else {
            Toast.makeText(this, "Storage permission is required to save videos", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)
//
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
//            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
//            insets
//        }

        // Pick video
        mBinding.btnImport.setOnClickListener {
            pickVideo()
        }

        // Export video
        mBinding.btnExport.setOnClickListener {
            selectedVideoUri?.let { uri ->
                if (checkAndRequestPermissions()) {
                    trimSelectedVideo(uri)
                }
            } ?: Toast.makeText(this, "Please import a video first", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        // Launch the intent using the ActivityResultLauncher
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
            // Android 13+ - no need for storage permissions for app-specific directories
            true
        } else {
            // Android 12 and below
            val writePermission = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            val readPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)

            if (writePermission != PackageManager.PERMISSION_GRANTED || readPermission != PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(arrayOf(
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ))
                false
            } else {
                true
            }
        }
    }

    private fun trimSelectedVideo(videoUri: Uri) {
        // Show progress dialog
        val progressDialog = AlertDialog.Builder(this)
            .setTitle("Processing Video")
            .setMessage("Trimming video, please wait...")
            .setCancelable(false)
            .create()
        progressDialog.show()

        // Get input path
        val inputPath = getRealPathFromURI(videoUri)
        if (inputPath == null) {
            progressDialog.dismiss()
            Toast.makeText(this, "Cannot process this video", Toast.LENGTH_SHORT).show()
            return
        }

        val startMs = mBinding.seekBarStart.progress.toLong()
        val endMs = mBinding.seekBarEnd.progress.toLong()
        val startTime = startMs.toDouble() / 1000
        val duration = (endMs - startMs).toDouble() / 1000

        // Validate trim parameters
        if (duration <= 0) {
            progressDialog.dismiss()
            Toast.makeText(this, "Invalid trim duration", Toast.LENGTH_SHORT).show()
            return
        }

        // Create output file
        val outputFile = createOutputFile()
        if (outputFile == null) {
            progressDialog.dismiss()
            Toast.makeText(this, "Cannot create output file", Toast.LENGTH_SHORT).show()
            return
        }

        // Perform trimming in background thread
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FFmpegHelper.trimVideo(inputPath, outputFile.absolutePath, startTime, duration)

                // Add to MediaStore so it appears in gallery
                val videoUri = addVideoToGallery(outputFile)

                runOnUiThread {
                    progressDialog.dismiss()
                    if (videoUri != null) {
                        Toast.makeText(this@MainActivity, "Video trimmed successfully and saved to gallery!", Toast.LENGTH_LONG).show()
                        Log.i(TAG, "Trimmed video saved: ${outputFile.absolutePath}")
                    } else {
                        Toast.makeText(this@MainActivity, "Video trimmed but failed to add to gallery", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    progressDialog.dismiss()
                    Toast.makeText(this@MainActivity, "Trim failed: ${e.message}", Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Trim failed", e)
                }
            }
        }
    }

    private fun getRealPathFromURI(uri: Uri): String? {
        return try {
            val filePathColumn = arrayOf(MediaStore.Video.Media.DATA)
            val cursor = contentResolver.query(uri, filePathColumn, null, null, null)

            if (cursor != null && cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val path = cursor.getString(columnIndex)
                cursor.close()
                path
            } else {
                // Fallback: try to copy the file to cache directory
                copyUriToFile(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get real path from URI", e)
            copyUriToFile(uri)
        }
    }

    private fun copyUriToFile(uri: Uri): String? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File(cacheDir, "temp_video_${System.currentTimeMillis()}.mp4")

            inputStream?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy URI to file", e)
            null
        }
    }

    private fun createOutputFile(): File? {
        return try {
            val videoDir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - use app-specific external storage
                File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "TrimmedVideos")
            } else {
                // Android 9 and below - use public Movies directory
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "VideoEditor")
            }

            if (!videoDir.exists()) {
                videoDir.mkdirs()
            }

            File(videoDir, "trimmed_${System.currentTimeMillis()}.mp4")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create output file", e)
            null
        }
    }

    private fun addVideoToGallery(videoFile: File): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, videoFile.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATA, videoFile.absolutePath)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoEditor")
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
            }

            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)

            // Notify MediaScanner to refresh gallery
            sendBroadcast(Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(videoFile)))

            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add video to gallery", e)
            null
        }
    }
}