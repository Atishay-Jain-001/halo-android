package app.halo.focus

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONArray
import org.json.JSONObject

/**
 * Shows ONLY the live Halo site. Other addresses open in the normal browser. The bridge object "HaloAndroid" is added by
 * the system for this one origin only, so no other site can talk to the app.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var reply: JavaScriptReplyProxy? = null
    private var resumed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MiniHub.page = this      // the floating timer's buttons are carried out by this page
        web = WebView(this)
        web.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
        }

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val u = request.url
                if (sameOrigin(u)) return false
                if (u.scheme == "http" || u.scheme == "https") {
                    try { startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) {}
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    val html = "<html><body style='background:#0F1220;color:#fff;font-family:sans-serif;text-align:center;padding:48px 24px'>" +
                        "<h2>Can't reach Halo</h2><p>Check your internet connection.</p>" +
                        "<p><a style='color:#7C8CFF;font-size:20px' href='${Prefs.ORIGIN}'>Try again</a></p></body></html>"
                    view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            }
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(web, "HaloAndroid", setOf(Prefs.ORIGIN)) { _, message, sourceOrigin, isMainFrame, proxy ->
                if (isMainFrame && sourceOrigin.toString() == Prefs.ORIGIN) onPageMessage(message, proxy)
            }
        }

        if (savedInstanceState == null) web.loadUrl(Prefs.ORIGIN)
        else web.restoreState(savedInstanceState)

        if (!Prefs.onboarded(this)) startActivity(Intent(this, SetupActivity::class.java))
    }

    private fun sameOrigin(u: Uri): Boolean {
        val o = Uri.parse(Prefs.ORIGIN)
        return u.scheme == o.scheme && u.host == o.host && (u.port == o.port || u.port == -1)
    }

    /* ---- messages from the Halo page ---- */
    private fun onPageMessage(m: WebMessageCompat, proxy: JavaScriptReplyProxy) {
        reply = proxy
        val text = m.data ?: return
        val o = try { JSONObject(text) } catch (e: Exception) { return }
        when (o.optString("t")) {
            "hello" -> pushState()
            "apps" -> sendApps()
            "set" -> {
                val arr = o.optJSONArray("blocked") ?: JSONArray()
                val set = HashSet<String>()
                for (i in 0 until minOf(arr.length(), 500)) set.add(arr.optString(i))
                Prefs.setSettings(this, o.optBoolean("enabled", true), set)
                if (resumed) BlockerService.ensure(this)
                pushState()
            }
            "session" -> {
                Prefs.setSession(this, o.optString("src"), o.optBoolean("on", false), o.optLong("until", 0L))
                if (resumed) BlockerService.ensure(this)
            }
            "link" -> {
                // The page hands over a "link key" (not a login): it lets the blocker ask the server about a running session by itself, even when Halo is closed.
                val ok = Prefs.setLink(this, o.optString("key"), o.optString("url"), o.optString("anon"), o.optString("device"), o.optString("uid"))
                if (ok && resumed) BlockerService.ensure(this)
                pushState()
            }
            "unlink" -> {
                Prefs.clearLink(this)      // logged out of Halo
                pushState()
            }
            "perms" -> startActivity(Intent(this, SetupActivity::class.java))
            "battery" -> startActivity(Intent(this, SetupActivity::class.java).putExtra("battery", true))
            "mini" -> {
                // The page's timer as plain data (null = no session). The floating bar draws it and keeps its own clock.
                MiniHub.onPageSnap(this, o.optJSONObject("snap")?.toString())
                if (resumed) BlockerService.ensure(this)
            }
            "minimsg" -> MiniHub.pageMsg(o.optString("text"))
            "minishow" -> MiniHub.show(this)
            "miniset" -> {
                Prefs.setMiniOn(this, o.optBoolean("on", true))
                if (resumed) BlockerService.ensure(this)
                MiniHub.changed(this)
                pushState()
            }
        }
    }

    /** Used by MiniHub: hand a command to the Halo page (the bar's button presses). false = the page can't be reached right now. */
    fun deliver(json: String): Boolean {
        val r = reply ?: return false
        return try { r.postMessage(json); true } catch (e: Exception) { false }
    }

    private fun pushState() {
        val o = JSONObject()
        o.put("t", "state")
        o.put("enabled", Prefs.enabled(this))
        o.put("blocked", JSONArray(Prefs.blocked(this).toList()))
        o.put("perms", JSONObject().put("overlay", Perms.overlay(this)).put("usage", Perms.usage(this)).put("battery", batteryOk()))
        o.put("srvAge", Prefs.serverAgeMs(this))
        o.put("linked", Prefs.linked(this))
        o.put("linkUid", Prefs.linkUid(this))
        o.put("linkDevice", Prefs.linkDevice(this))
        o.put("mini", true)      // this app version can draw the floating timer bar
        o.put("miniOn", Prefs.miniOn(this))
        post(o)
    }

    private fun batteryOk(): Boolean =
        try { (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) } catch (e: Exception) { true }

    private fun sendApps() {
        Thread {
            val pm = packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val arr = JSONArray()
            val seen = HashSet<String>()
            for (ri in pm.queryIntentActivities(i, 0)) {
                val p = ri.activityInfo.packageName
                if (p == packageName || !seen.add(p)) continue
                arr.put(JSONObject().put("p", p).put("n", ri.loadLabel(pm).toString()))
            }
            val o = JSONObject().put("t", "apps").put("list", arr)
            runOnUiThread { post(o) }
        }.start()
    }

    private fun post(o: JSONObject) {
        try { reply?.postMessage(o.toString()) } catch (e: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        MiniHub.haloShown = true      // the big timer is right here: the floating bar steps aside
        BlockerService.ensure(this)
        MiniHub.changed(this)
        pushState()      // permissions may have just been changed in Settings
    }

    override fun onPause() {
        resumed = false
        MiniHub.haloShown = false      // left Halo: the floating bar appears (if a session is running)
        MiniHub.changed(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (MiniHub.page === this) MiniHub.page = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
