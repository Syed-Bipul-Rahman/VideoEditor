package me.bipul.videoeditor

import android.app.Activity
import android.content.ContentValues.TAG
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import me.bipul.videoeditor.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var mBinding: ActivityMainBinding
    private val REQUEST_CODE_PICK_VIDEO = 1
    private var selectedVideoUri: Uri? = null
    private var videoDuration = 0L

    //val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        mBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mBinding.root)


//pick video
        mBinding.btnImport.setOnClickListener {
            pickVideo()
        }
//export video
        mBinding.btnExport.setOnClickListener {
            selectedVideoUri?.let { uri ->
                trimSelectedVideo(uri)
            } ?: Toast.makeText(this, "Please import a video first", Toast.LENGTH_SHORT).show()
        }

        // Register Activity Result Launcher
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.also { uri ->
                    selectedVideoUri = uri
                    setupVideoPreview(uri)
                }
            }
        }

    }

    private fun pickVideo() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
        }
        startActivity(intent)
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
    private fun trimSelectedVideo(videoUri: Uri) {
        val contentResolver = contentResolver
        val inputStream = contentResolver.openInputStream(videoUri)
        val filePathColumn = arrayOf(MediaStore.Video.Media.DATA)
        val cursor = contentResolver.query(videoUri, filePathColumn, null, null, null)

        val inputPath: String = if (cursor != null && cursor.moveToFirst()) {
            val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
            cursor.getString(columnIndex).also { cursor.close() }
        } else {
            Log.e(TAG, "Failed to get file path from URI")
            Toast.makeText(this, "Cannot process this video", Toast.LENGTH_SHORT).show()
            return
        }

        val startMs = mBinding.seekBarStart.progress.toLong()
        val endMs = mBinding.seekBarEnd.progress.toLong()
        val startTime = startMs.toDouble() / 1000
        val endTime = endMs.toDouble() / 1000

        val outputDir = File(getExternalFilesDir(null), "trimmed_videos")
        if (!outputDir.exists()) outputDir.mkdirs()
        val outputFile = File(outputDir, "trimmed_${System.currentTimeMillis()}.mp4")
        val outputPath = outputFile.absolutePath

//        FFmpegHelper.trimVideo(inputPath, outputPath, startTime, endTime) { success ->
//            runOnUiThread {
//                if (success) {
//                    Toast.makeText(this, "Trim successful: $outputPath", Toast.LENGTH_LONG).show()
//                } else {
//                    Toast.makeText(this, "Trim failed", Toast.LENGTH_SHORT).show()
//                }
//            }
//        }
    }

}