package com.example.screenrecorder

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var projectionManager: MediaProjectionManager

    private val folderPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            showState(ScreenRecordService.STATE_CANCELLED)
            return@registerForActivityResult
        }
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        getPreferences(MODE_PRIVATE).edit().putString(KEY_FOLDER, uri.toString()).apply()
        showState(ScreenRecordService.STATE_PERMISSION)
        projectionPermission.launch(projectionManager.createScreenCaptureIntent())
    }

    private val projectionPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            showState(ScreenRecordService.STATE_CANCELLED)
            return@registerForActivityResult
        }
        val folder = getPreferences(MODE_PRIVATE).getString(KEY_FOLDER, null)
        if (folder == null) {
            showState(ScreenRecordService.STATE_ERROR)
            return@registerForActivityResult
        }
        val service = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, result.data)
            putExtra(ScreenRecordService.EXTRA_FOLDER_URI, folder)
        }
        startForegroundService(service)
    }

    private val notificationPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { folderPicker.launch(null) }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showState(intent?.getStringExtra(ScreenRecordService.EXTRA_STATE))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        projectionManager = getSystemService(MediaProjectionManager::class.java)
        statusText = findViewById(R.id.statusText)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        startButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                !getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                folderPicker.launch(null)
            }
        }
        stopButton.setOnClickListener {
            startService(Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            })
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ScreenRecordService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stateReceiver, filter)
        }
        showState(getSharedPreferences(ScreenRecordService.PREFS, MODE_PRIVATE)
            .getString(ScreenRecordService.KEY_STATE, ScreenRecordService.STATE_READY))
    }

    override fun onStop() {
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    private fun showState(state: String?) {
        val recording = state == ScreenRecordService.STATE_RECORDING
        startButton.isEnabled = !recording
        stopButton.isEnabled = recording
        statusText.setText(when (state) {
            ScreenRecordService.STATE_PERMISSION -> R.string.status_permission
            ScreenRecordService.STATE_RECORDING -> R.string.status_recording
            ScreenRecordService.STATE_SAVED -> R.string.status_saved
            ScreenRecordService.STATE_CANCELLED -> R.string.status_cancelled
            ScreenRecordService.STATE_ERROR -> R.string.status_error
            else -> R.string.status_ready
        })
    }

    companion object {
        private const val KEY_FOLDER = "folder"
    }
}
