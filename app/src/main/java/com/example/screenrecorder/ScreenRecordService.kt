package com.example.screenrecorder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputUri: Uri? = null
    private var stopping = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            if (!stopping) stopRecording(true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording(intent)
            ACTION_STOP -> stopRecording(true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    private fun startRecording(intent: Intent) {
        if (projection != null) return
        startAsForeground()
        try {
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, RESULT_MISSING)
            val resultData = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_RESULT_DATA)
            } ?: error("MediaProjection permission is missing")
            val folderUri = Uri.parse(intent.getStringExtra(EXTRA_FOLDER_URI)
                ?: error("Output folder is missing"))

            val displayMetrics = resources.displayMetrics
            val windowManager = getSystemService(WindowManager::class.java)
            val bounds = if (Build.VERSION.SDK_INT >= 30) {
                windowManager.maximumWindowMetrics.bounds
            } else null
            val width = makeEven(bounds?.width() ?: displayMetrics.widthPixels)
            val height = makeEven(bounds?.height() ?: displayMetrics.heightPixels)
            val density = displayMetrics.densityDpi

            outputUri = createOutputFile(folderUri)
            outputDescriptor = contentResolver.openFileDescriptor(outputUri!!, "w")
                ?: error("Cannot open output file")
            recorder = createRecorder(width, height, outputDescriptor!!)

            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData).also {
                it.registerCallback(projectionCallback, Handler(Looper.getMainLooper()))
            }
            virtualDisplay = projection!!.createVirtualDisplay(
                "ScreenRecorderDisplay", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder!!.surface, null, null
            )
            recorder!!.start()
            publishState(STATE_RECORDING)
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start screen recording", error)
            stopRecording(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(width: Int, height: Int, descriptor: ParcelFileDescriptor): MediaRecorder {
        val mediaRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
        return mediaRecorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate((width.toLong() * height * 5).coerceAtMost(20_000_000).toInt())
            setOutputFile(descriptor.fileDescriptor)
            prepare()
        }
    }

    private fun createOutputFile(folderUri: Uri): Uri {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return DocumentsContract.createDocument(
            contentResolver,
            DocumentsContract.buildDocumentUriUsingTree(
                folderUri, DocumentsContract.getTreeDocumentId(folderUri)
            ),
            "video/mp4",
            "screen_$stamp.mp4"
        ) ?: error("Cannot create a video in the selected folder")
    }

    private fun stopRecording(save: Boolean) {
        if (stopping) return
        stopping = true
        var completed = save
        try {
            if (save) recorder?.stop()
        } catch (error: RuntimeException) {
            completed = false
            Log.e(TAG, "The recording was too short to save", error)
        }
        recorder?.reset()
        recorder?.release()
        recorder = null
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        outputDescriptor?.close()
        outputDescriptor = null
        if (!completed) outputUri?.let { contentResolver.delete(it, null, null) }
        outputUri = null
        publishState(if (completed) STATE_SAVED else STATE_ERROR)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val stopIntent = Intent(this, ScreenRecordService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setColor(Color.RED)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop), stopPendingIntent)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun publishState(state: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATE, state).apply()
        sendBroadcast(Intent(ACTION_STATUS).setPackage(packageName).putExtra(EXTRA_STATE, state))
    }

    override fun onDestroy() {
        if (projection != null || recorder != null) stopRecording(false)
        super.onDestroy()
    }

    private fun makeEven(value: Int) = value - value % 2

    companion object {
        const val ACTION_START = "com.example.screenrecorder.START"
        const val ACTION_STOP = "com.example.screenrecorder.STOP"
        const val ACTION_STATUS = "com.example.screenrecorder.STATUS"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_FOLDER_URI = "folder_uri"
        const val EXTRA_STATE = "state"
        const val PREFS = "recording_state"
        const val KEY_STATE = "state"
        const val STATE_READY = "ready"
        const val STATE_PERMISSION = "permission"
        const val STATE_RECORDING = "recording"
        const val STATE_SAVED = "saved"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_ERROR = "error"
        private const val CHANNEL_ID = "screen_recording"
        private const val NOTIFICATION_ID = 1001
        private const val RESULT_MISSING = Int.MIN_VALUE
        private const val TAG = "ScreenRecordService"
    }
}
