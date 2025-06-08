package me.bipul.videoeditor



object FFmpegHelper {

    const val RETURN_CODE_SUCCESS = 0

//    fun trimVideo(
//        inputPath: String,
//        outputPath: String,
//        startTime: Double,
//        endTime: Double,
//        callback: (success: Boolean) -> Unit
//    ) {
//        // FFmpeg command to trim video without re-encoding (fast)
//        val command = "-i $inputPath -ss $startTime -to $endTime -c copy $outputPath"
//
//        FFmpeg.executeAsync(command) { returnCode ->
//            callback.invoke(returnCode == ReturnCode.SUCCESS)
//        }
//    }
}