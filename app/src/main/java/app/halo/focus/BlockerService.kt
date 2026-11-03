package app.halo.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs as a foreground service (you see its small notification). While a focus session is active it checks which app is
 * in front, and if it is one you chose, covers it with the "Stay focused" screen.
 *
 * It asks the Halo server by itself (every ~12 s while the screen is on) whether a focus session is running on any of your
 * devices, so blocking works even when the Halo page is asleep or Halo is not open at all.
 */
class BlockerService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null
    private var overlayPkg: String? = null

    /** Which screens are on top right now: "package/activity" -> when it came to the front. An entry is removed only when that screen is really left. */
    private val resumed = HashMap<String, Long>()

    /** package -> when the person tapped one of the two buttons on the cover (so it does not pop straight back while Android catches up). */
    private val dismissed = HashMap<String, Long>()
    private var lastCheck = 0L
    private var lastNote = ""
    private var started = false
    private var screenOn = true
    private var lastPoll = -100_000L
    private var lastTouch = -100_000L
    private val netBusy = AtomicBoolean(false)
    private var io: ExecutorService? = null
    private var screenReceiver: BroadcastReceiver? = null

    private val tick = object : Runnable {
        override fun run() {
            var next = 4000L
            try { next = doTick() } catch (e: Exception) { /* never crash the blocker */ }
            handler.postDelayed(this, next)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        io = Executors.newSingleThreadExecutor()
        registerScreenReceiver()
    }

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
        try { screenReceiver?.let { unregisterReceiver(it) } } catch (e: Exception) {}
        screenReceiver = null
        try { io?.shutdownNow() } catch (e: Exception) {}
        started = false
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenOn = pm.isInteractive
            val r = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action == Intent.ACTION_SCREEN_ON) {
                        screenOn = true
                        maybePoll(true)                       // the phone was just picked up: check the server right now
                        handler.removeCallbacks(tick)
                        handler.post(tick)
                    } else if (i.action == Intent.ACTION_SCREEN_OFF) {
                        screenOn = false
                    }
                }
            }
            val f = IntentFilter()
            f.addAction(Intent.ACTION_SCREEN_ON)
            f.addAction(Intent.ACTION_SCREEN_OFF)
            ContextCompat.registerReceiver(this, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenReceiver = r
        } catch (e: Exception) { screenOn = true }
    }

    /** Returns how long to wait before the next look. */
    private fun doTick(): Long {
        if (!Prefs.blockingWanted(this)) { stopSelf(); return 4000L }
        if (!Perms.both(this)) {
            note("Halo needs permissions to block apps. Tap to fix.")
            hideOverlay(); return 4000L
        }
        maybePoll(false)
        val active = Prefs.sessionActive(this)
        if (!screenOn) {                       // nobody is looking at the phone: nothing to cover (the picture of open apps is caught up when the screen turns on)
            hideOverlay()
            note(if (active) "Focus session running. Chosen apps are blocked." else "Halo is ready to protect your focus")
            return 4000L
        }
        val front = blockedFront()
        if (!active) {
            note("Halo is ready to protect your focus")
            hideOverlay()
            if (front != null) { maybePoll(true); return 1500L }      // a chosen app was just opened: ask the server straight away, don't wait for the next check
            return 4000L
        }
        note("Focus session running. Chosen apps are blocked.")
        if (front != null) showOverlay(front) else hideOverlay()
        return 1000L
    }

    /**
     * Which of the chosen apps is on screen right now (or null)?
     * Not "the last app that came to the front" but "which screens are open and have not been left yet". So half-swiping out of
     * an app and coming back keeps the cover (or brings it back), and the cover goes only when the app is really left.
     */
    @Suppress("DEPRECATION")
    private fun blockedFront(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val from = if (lastCheck == 0L) now - 6 * 3_600_000L else lastCheck - 2_000L
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            val p = e.packageName ?: continue
            val key = p + "/" + (e.className ?: "")
            when (e.eventType) {
                1 -> resumed[key] = e.timeStamp                 // screen came to the front (MOVE_TO_FOREGROUND / ACTIVITY_RESUMED)
                2, 23, 24 -> resumed.remove(key)                // left: paused, stopped or destroyed
            }
        }
        lastCheck = now
        val blocked = Prefs.blocked(this)
        var best: String? = null
        var bestTs = 0L
        for ((key, ts) in resumed) {
            val pkg = key.substringBefore('/')
            if (pkg == packageName || !blocked.contains(pkg)) continue
            if (ts <= (dismissed[pkg] ?: 0L)) continue      // the cover was closed with a button after this screen came up
            if (ts >= bestTs) { best = pkg; bestTs = ts }
        }
        return best
    }

    /** Asks the server (on a background thread) whether a session is running anywhere. force = skip the usual waiting time (but never more than once per 3 s). */
    private fun maybePoll(force: Boolean) {
        val link = Prefs.link(this) ?: return
        val now = SystemClock.elapsedRealtime()
        val gap = if (force) 3_000L else if (screenOn) 12_000L else 60_000L
        if (now - lastPoll < gap) return
        if (!netBusy.compareAndSet(false, true)) return
        lastPoll = now
        val touch = Prefs.localLive(this) && now - lastTouch >= 25_000L
        if (touch) lastTouch = now
        val ctx = applicationContext
        val ex = io
        if (ex == null) { netBusy.set(false); return }
        try {
            ex.execute {
                try {
                    val st = Net.status(link)
                    var cleared = false
                    if (st != null) {
                        if (!st.linked) { Prefs.clearLink(ctx); cleared = true }      // the key was revoked (logged out elsewhere): forget it; the Halo page will link again
                        else Prefs.setServer(ctx, st.live, st.device)
                    }
                    if (touch && !cleared) {
                        // This phone's own Halo page may be asleep: keep its session visible to your other devices, and notice if it is really gone.
                        val ok = Net.touch(link)
                        if (ok == false && Prefs.localSilentMs(ctx) > 90_000L) Prefs.setLocalOff(ctx)
                    }
                } catch (e: Exception) {
                } finally {
                    netBusy.set(false)
                }
            }
        } catch (e: Exception) { netBusy.set(false) }
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
        root.addView(btn("Back to Halo", true) { dismissCover(pkg); openHalo() }.also {
            (it.layoutParams as LinearLayout.LayoutParams).topMargin = (28 * dp).toInt()
        })
        root.addView(btn("Go to home screen", false) {
            dismissCover(pkg)
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

    /** The person chose to leave: close the cover, and don't bring it back for that app until it is opened again. */
    private fun dismissCover(pkg: String) {
        dismissed[pkg] = System.currentTimeMillis()
        hideOverlay()
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

        /** Call while the app is on screen, or from the boot receiver (Android refuses to start it from the background otherwise). */
        fun ensure(c: Context) {
            if (Prefs.blockingWanted(c) && Perms.both(c)) {
                try { ContextCompat.startForegroundService(c, Intent(c, BlockerService::class.java)) } catch (e: Exception) {}
            }
        }
    }
}
