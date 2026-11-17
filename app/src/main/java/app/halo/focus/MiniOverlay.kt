package app.halo.focus

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The floating timer bar: a small window over your other apps that holds a WebView showing mini.html from the Halo site.
 * mini.html uses the SAME mini-ui.js as the pop-out timer on a computer, so the look and the buttons are the same code.
 * This class only does the things a web page cannot: the window itself, moving it, the cross that closes it, and sizing it.
 *
 *   idle  = a slim bar (just the time, like the idle pop-out on a computer)
 *   open  = a tap makes the window bigger so the buttons (what appears on hover on a computer) fit; it shrinks again by itself
 *   drag  = hold and move it anywhere; a cross appears at the bottom, drop the bar on it to hide the bar (the timer keeps running)
 */
class MiniOverlay(context: Context) {
    private val app: Context = context.applicationContext
    private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = app.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())

    private val slimW = (210 * dp).toInt()
    private val slimH = (44 * dp).toInt()
    private val openW = (300 * dp).toInt()
    private val openH = (96 * dp).toInt()
    private val crossSize = (84 * dp).toInt()
    private val crossReach = 76 * dp      // the bar counts as "on the cross" when its middle is this close to the cross's middle

    private var root: BarRoot? = null
    private var web: WebView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var attached = false
    private var ready = false
    private var failedAt = 0L
    private var loadAt = 0L      // when the bar page was last asked to load
    private var lastErr = ""      // last problem loading the bar page (for the status line)
    private var attachErr = ""      // last problem putting the bar on screen
    private var isOpen = false
    private var lastJson = ""
    private var anchorX = 0f      // where the bar's middle belongs (the open window grows around it, and goes back to it)
    private var anchorY = 0f
    private var sizeAnim: ValueAnimator? = null
    private var overSince = 0L

    private var cross: CrossView? = null
    private var crossLp: WindowManager.LayoutParams? = null
    private var overCross = false

    fun isShown(): Boolean = attached

    /** One line: is the bar's page loaded, is its window on screen, and what went wrong if not. */
    fun diag(): String {
        val page = if (web == null) "not created" else if (ready) "loaded" else if (failedAt != 0L) "FAILED to load (" + lastErr + ")" else "loading"
        return page + ", window " + (if (attached) "on screen" else "hidden") + (if (attachErr.isNotEmpty()) " (" + attachErr + ")" else "")
    }

    /* ---------------- show / hide ---------------- */

    /** Draw (or update) the bar with this timer picture. Called by the service on every look, so it must be cheap when nothing changed. */
    fun show(json: String) {
        overSince = 0L
        if (!ensureWeb()) return
        if (!ready) {
            // Not loaded yet. If it failed (internet down, error page) try again after 10 s; if it just never answered, after 25 s.
            val now = SystemClock.elapsedRealtime()
            if ((failedAt != 0L && now - failedAt > 10_000L) || (failedAt == 0L && now - loadAt > 25_000L)) {
                failedAt = 0L
                loadAt = now
                web?.loadUrl(Prefs.ORIGIN + "/mini.html")
            }
            return
        }
        attachIfNeeded()
        if (json != lastJson) {
            lastJson = json
            js("HaloMiniApp.state(" + JSONObject.quote(json) + ")")
        }
    }

    /** Take the bar off the screen. sessionOver = nothing is running any more: after 30 s the page is thrown away too. */
    fun hide(sessionOver: Boolean) {
        removeCross()
        if (attached) {
            sizeAnim?.cancel()
            try { root?.let { wm.removeView(it) } } catch (e: Exception) { }
            attached = false
            try { web?.onPause() } catch (e: Exception) { }
            if (isOpen) { isOpen = false; js("HaloMiniApp.collapse()") }
        }
        if (sessionOver) {
            val now = SystemClock.elapsedRealtime()
            if (overSince == 0L) overSince = now
            else if (now - overSince > 30_000L) destroyWeb()
        } else overSince = 0L
    }

    fun destroy() {
        hide(false)
        destroyWeb()
    }

    /** A short message from the timer ("Added 25m to today 🔥") slides over the bar. */
    fun msg(text: String) {
        if (attached && ready) js("HaloMiniApp.msg(" + JSONObject.quote(text) + ")")
    }

    /** The screen was rotated / resized: put the bar back where it belongs inside the new screen. */
    fun reclamp() {
        if (!attached) return
        val p = lp ?: return
        val r = root ?: return
        sizeAnim?.cancel()
        loadAnchor()
        val pt = clamp((anchorX - p.width / 2f).toInt(), (anchorY - p.height / 2f).toInt(), p.width, p.height)
        p.x = pt.x; p.y = pt.y
        try { wm.updateViewLayout(r, p) } catch (e: Exception) { }
    }

    /* ---------------- the page inside the window ---------------- */

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWeb(): Boolean {
        if (web != null) return true
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        val w = try { WebView(ContextThemeWrapper(app, R.style.AppTheme)) } catch (e: Exception) { return false }
        w.setBackgroundColor(Color.TRANSPARENT)
        w.isVerticalScrollBarEnabled = false
        w.isHorizontalScrollBarEnabled = false
        w.overScrollMode = View.OVER_SCROLL_NEVER
        w.isLongClickable = false
        w.setOnLongClickListener { true }
        w.isHapticFeedbackEnabled = false
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.setSupportMultipleWindows(false)
        w.settings.javaScriptCanOpenWindowsAutomatically = false
        w.settings.setSupportZoom(false)
        w.webViewClient = object : WebViewClient() {
            // Pages on the Halo site may load (some hosts redirect /mini.html to /mini); anything else is refused.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                val o = Uri.parse(Prefs.ORIGIN)
                return !(u.scheme == o.scheme && u.host == o.host && u.port == o.port)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { ready = false; failedAt = SystemClock.elapsedRealtime(); lastErr = error.description?.toString() ?: "error " + error.errorCode }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) { ready = false; failedAt = SystemClock.elapsedRealtime(); lastErr = "HTTP " + errorResponse.statusCode }
            }
        }
        // Only mini.html on the Halo site can talk to the app, through this one named door.
        WebViewCompat.addWebMessageListener(w, "HaloMiniHost", setOf(Prefs.ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
            if (isMainFrame && sourceOrigin.toString() == Prefs.ORIGIN) onPage(message.data)
        }
        val r = BarRoot(app)
        r.addView(w, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        web = w
        root = r
        ready = false
        failedAt = 0L
        lastJson = ""
        loadAt = SystemClock.elapsedRealtime()
        w.loadUrl(Prefs.ORIGIN + "/mini.html")
        return true
    }

    private fun destroyWeb() {
        sizeAnim?.cancel()
        val w = web
        val r = root
        web = null
        root = null
        ready = false
        lastJson = ""
        try { r?.removeAllViews() } catch (e: Exception) { }
        try { w?.destroy() } catch (e: Exception) { }
    }

    private fun js(code: String) {
        try { web?.evaluateJavascript("try{" + code + "}catch(e){}", null) } catch (e: Exception) { }
    }

    /** Messages from mini.html. */
    private fun onPage(text: String?) {
        val o = try { JSONObject(text ?: return) } catch (e: Exception) { return }
        when (o.optString("t")) {
            "ready" -> { ready = true; lastJson = ""; MiniHub.changed(app) }      // the page is up: let the service draw the bar now
            "open" -> setOpen(o.optBoolean("on", false))
            "act" -> MiniHub.command(app, o.optString("a"), o.optString("id"))
            "poke" -> MiniHub.command(app, "poke", "")
            "openHalo" -> MiniHub.openHalo(app)
        }
    }

    /* ---------------- the window ---------------- */

    private fun newParams(w: Int, h: Int, x: Int, y: Int): WindowManager.LayoutParams {
        val p = WindowManager.LayoutParams(
            w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = x
        p.y = y
        return p
    }

    private fun attachIfNeeded() {
        if (attached) return
        val r = root ?: return
        isOpen = false
        loadAnchor()
        val pt = clamp((anchorX - slimW / 2f).toInt(), (anchorY - slimH / 2f).toInt(), slimW, slimH)
        val p = newParams(slimW, slimH, pt.x, pt.y)
        lp = p
        try {
            wm.addView(r, p)
            attached = true
            attachErr = ""
            try { web?.onResume() } catch (e: Exception) { }
        } catch (e: Exception) { attached = false; attachErr = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(70) }
    }

    /** Where the bar's middle belongs: the saved spot, or bottom-centre just above the navigation bar. */
    private fun loadAnchor() {
        val s = screenSize()
        val pos = Prefs.miniPos(app)
        if (pos != null) {
            anchorX = pos[0] * s[0]
            anchorY = pos[1] * s[1]
        } else {
            anchorX = s[0] / 2f
            anchorY = s[1] - insets().bottom - 28 * dp - slimH / 2f
        }
    }

    private fun setOpen(on: Boolean) {
        if (!attached || isOpen == on) return
        isOpen = on
        animateTo(if (on) openW else slimW, if (on) openH else slimH)
    }

    private fun animateTo(tw: Int, th: Int) {
        val p = lp ?: return
        val r = root ?: return
        sizeAnim?.cancel()
        val w0 = p.width
        val h0 = p.height
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = 170L
        a.addUpdateListener { v ->
            val f = v.animatedValue as Float
            val w = (w0 + (tw - w0) * f).toInt()
            val h = (h0 + (th - h0) * f).toInt()
            val pt = clamp((anchorX - w / 2f).toInt(), (anchorY - h / 2f).toInt(), w, h)
            p.width = w; p.height = h; p.x = pt.x; p.y = pt.y
            try { if (attached) wm.updateViewLayout(r, p) } catch (e: Exception) { }
        }
        sizeAnim = a
        a.start()
    }

    /* ---------------- screen maths ---------------- */

    private fun screenSize(): IntArray {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val b = wm.currentWindowMetrics.bounds
                if (b.width() > 0 && b.height() > 0) return intArrayOf(b.width(), b.height())
            } catch (e: Exception) { }
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return intArrayOf(dm.widthPixels, dm.heightPixels)
    }

    /** Status bar / navigation bar sizes: the bar is kept out of them. */
    private fun insets(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val i = wm.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
                if (i.top > 0 || i.bottom > 0 || i.left > 0 || i.right > 0) return Rect(i.left, i.top, i.right, i.bottom)
            } catch (e: Exception) { }
        }
        val r = app.resources
        fun dim(n: String): Int {
            val id = r.getIdentifier(n, "dimen", "android")
            return if (id > 0) r.getDimensionPixelSize(id) else 0
        }
        return Rect(0, dim("status_bar_height"), 0, dim("navigation_bar_height"))
    }

    private fun clamp(x: Int, y: Int, w: Int, h: Int): Point {
        val s = screenSize()
        val ins = insets()
        val minX = ins.left
        val maxX = max(minX, s[0] - ins.right - w)
        val minY = ins.top
        val maxY = max(minY, s[1] - ins.bottom - h)
        return Point(min(max(x, minX), maxX), min(max(y, minY), maxY))
    }

    /* ---------------- dragging, and the cross ---------------- */

    private fun startDrag() {
        sizeAnim?.cancel()
        showCross()
    }

    private fun dragTo(x: Int, y: Int) {
        val p = lp ?: return
        val r = root ?: return
        val pt = clamp(x, y, p.width, p.height)
        p.x = pt.x
        p.y = pt.y
        try { wm.updateViewLayout(r, p) } catch (e: Exception) { }
        updateCross()
    }

    private fun endDrag(cancelled: Boolean) {
        val p = lp
        val onCross = overCross && !cancelled
        removeCross()
        if (p == null) return
        if (onCross) {
            handler.post { MiniHub.dismiss(app) }      // (after this touch has finished: the window is about to be removed)
            return
        }
        val s = screenSize()
        anchorX = p.x + p.width / 2f
        anchorY = p.y + p.height / 2f
        Prefs.setMiniPos(app, anchorX / s[0], anchorY / s[1])
    }

    private fun crossCentre(): Point {
        val s = screenSize()
        return Point(s[0] / 2, s[1] - insets().bottom - (64 * dp).toInt())
    }

    private fun showCross() {
        if (cross != null) return
        val v = CrossView(app)
        val c = crossCentre()
        val p = WindowManager.LayoutParams(
            crossSize, crossSize, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = Gravity.TOP or Gravity.START
        p.x = c.x - crossSize / 2
        p.y = c.y - crossSize / 2
        v.alpha = 0f
        try {
            wm.addView(v, p)
            cross = v
            crossLp = p
            overCross = false
            v.animate().alpha(1f).setDuration(150L).start()
        } catch (e: Exception) { cross = null; crossLp = null }
    }

    private fun updateCross() {
        val v = cross ?: return
        val p = lp ?: return
        val c = crossCentre()
        val d = hypot((p.x + p.width / 2f) - c.x, (p.y + p.height / 2f) - c.y)
        val over = d < crossReach
        if (over != overCross) {
            overCross = over
            v.setActive(over)
            if (over) { try { v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) } catch (e: Exception) { } }
        }
    }

    private fun removeCross() {
        val v = cross
        cross = null
        crossLp = null
        overCross = false
        if (v != null) { try { wm.removeView(v) } catch (e: Exception) { } }
    }

    /** The round "✕" that appears at the bottom while the bar is being dragged. */
    private inner class CrossView(c: Context) : View(c) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        private var active = false

        init {
            ring.style = Paint.Style.STROKE
            ring.strokeWidth = 1.5f * dp
            ring.color = Color.argb(110, 255, 255, 255)
            stroke.style = Paint.Style.STROKE
            stroke.strokeWidth = 2.8f * dp
            stroke.strokeCap = Paint.Cap.ROUND
            stroke.color = Color.WHITE
        }

        fun setActive(on: Boolean) {
            active = on
            invalidate()
            animate().scaleX(if (on) 1.28f else 1f).scaleY(if (on) 1.28f else 1f).setDuration(120L).start()
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f - 14 * dp
            fill.color = if (active) Color.argb(240, 229, 72, 77) else Color.argb(205, 21, 23, 28)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            val d = r * 0.34f
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, stroke)
            canvas.drawLine(cx + d, cy - d, cx - d, cy + d, stroke)
        }
    }

    /**
     * The window's content. It lets the page handle taps and buttons, but once a finger has moved far enough it takes over
     * (the page gets a "cancel") and moves the whole window, and it tells the page when you tap outside the open bar.
     */
    private inner class BarRoot(c: Context) : FrameLayout(c) {
        private val slop = ViewConfiguration.get(c).scaledTouchSlop * 1.5f
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp?.x ?: 0; startY = lp?.y ?: 0
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (!dragging && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                    dragging = true
                    startDrag()
                    return true
                }
            }
            return false
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (!dragging) return super.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> dragTo(startX + (e.rawX - downX).toInt(), startY + (e.rawY - downY).toInt())
                MotionEvent.ACTION_UP -> { dragging = false; endDrag(false) }
                MotionEvent.ACTION_CANCEL -> { dragging = false; endDrag(true) }
            }
            return true
        }

        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                if (isOpen) js("HaloMiniApp.collapse()")      // a tap anywhere else closes the buttons
                return true
            }
            return super.dispatchTouchEvent(e)
        }
    }
}
