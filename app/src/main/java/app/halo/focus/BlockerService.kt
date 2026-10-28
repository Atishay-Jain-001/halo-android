package app.halo.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Runs as a foreground service (you see its small notification). While a focus session is active it checks which app is
 * in front, and if it is one you chose, covers it with the "Stay focused" screen.
 */
class BlockerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayPkg: String? = null
    private var cur: String? = null
    private var lastCheck = 0L
    private var lastNote = ""
    private var started = false

    private val tick = object : Runnable {
        override fun run() {
            try { doTick() } catch (e: Exception) { /* never crash the blocker */ }
            handler.postDelayed(this, if (Prefs.sessionActive(this@BlockerService)) 1000L else 4000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        makeChannel()
        val n = buildNote("Halo is ready to protect your focus")
        lastNote = "Halo is ready to protect your focus"
        try {
            if (Build.VERSION.SDK_INT >= 34)
                ServiceCompat.startForeground(this, NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else
                startForeground(NOTE_ID, n)
        } catch (e: Exception) {
            stopSelf(); return START_NOT_STICKY
        }
        if (!started) { started = true; handler.post(tick) }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        started = false
        super.onDestroy()
    }

    private fun doTick() {
        if (!Prefs.blockingWanted(this)) { stopSelf(); return }
        if (!Perms.both(this)) {
            note("Halo needs permissions to block apps. Tap to fix.")
            hideOverlay(); return
        }
        if (!Prefs.sessionActive(this)) {
            note("Halo is ready to protect your focus")
            hideOverlay(); cur = null; lastCheck = 0L; return
        }
        note("Focus session running. Chosen apps are blocked.")
        val front = detectForeground()
        if (front != null && front != packageName && Prefs.blocked(this).contains(front)) showOverlay(front) else hideOverlay()
    }

    @Suppress("DEPRECATION")
    private fun detectForeground(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastCheck == 0L) now - 3_600_000L else lastCheck - 2_000L
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) cur = e.packageName
        }
        lastCheck = now
        return cur
    }

    private fun showOverlay(pkg: String) {
        if (overlay != null && overlayPkg == pkg) return
        hideOverlay()
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) { "This app" }
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F20F1220"))
            setPadding((32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt(), (32 * dp).toInt())
            isClickable = true
        }
        fun tv(text: String, size: Float, bold: Boolean, color: String) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.parseColor(color)); gravity = Gravity.CENTER
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(tv("🎯", 56f, false, "#FFFFFF"))
        root.addView(tv("Stay focused", 30f, true, "#FFFFFF").apply { setPadding(0, (12 * dp).toInt(), 0, (8 * dp).toInt()) })
        root.addView(tv("$label is blocked while your Halo focus session is running.", 16f, false, "#C9CEE8"))
        fun btn(text: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                if (filled) setColor(Color.parseColor("#7C8CFF")) else { setColor(Color.TRANSPARENT); setStroke((2 * dp).toInt(), Color.parseColor("#7C8CFF")) }
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (52 * dp).toInt()).apply { topMargin = (14 * dp).toInt() }
        }
        root.addView(btn("Back to Halo", true) { openHalo() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = (28 * dp).toInt()
        })
        root.addView(btn("Go to home screen", false) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        try {
            (getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(root, lp)
            overlay = root; overlayPkg = pkg
        } catch (e: Exception) { overlay = null; overlayPkg = null }
    }

    private fun hideOverlay() {
        val v = overlay ?: return
        try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (e: Exception) {}
        overlay = null; overlayPkg = null
    }

    private fun openHalo() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    private fun makeChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Focus blocker", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows that Halo is ready to block distracting apps during a focus session"
                setShowBadge(false)
            })
        }
    }

    private fun buildNote(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notif).setContentTitle("Halo").setContentText(text)
            .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun note(text: String) {
        if (text == lastNote) return
        lastNote = text
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTE_ID, buildNote(text))
    }

    companion object {
        private const val CHANNEL = "halo_blocker"
        private const val NOTE_ID = 1

        /** Call only while the app is on screen (Android refuses to start it from the background). */
        fun ensure(c: Context) {
            if (Prefs.blockingWanted(c) && Perms.both(c)) {
                try { ContextCompat.startForegroundService(c, Intent(c, BlockerService::class.java)) } catch (e: Exception) {}
            }
        }
    }
}
