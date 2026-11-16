package app.halo.focus

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import org.json.JSONObject

/**
 * The go-between for the floating timer bar. Everything here runs on the main thread.
 *
 * Where the picture of the timer comes from (first one that applies):
 *   1. This phone's own Halo page: it sends the timer as plain data every time something changes, and a heartbeat every ~20 s
 *      (see android-link.js). The bar keeps its own clock from that, so it needs no further messages to count.
 *   2. The server: a session running on another device (your laptop). The service asks for it every few seconds.
 * The bar only ever draws this data. Its buttons are sent back to the Halo page, which does the real work (the same
 * functions the pop-out timer on a computer uses). Nothing is counted or saved here.
 */
object MiniHub {
    private class Remote(val row: Net.Row, val at: Long)      // at = phone clock when the server answered

    private var remote: Remote? = null
    private var serverOkAt = 0L      // when the server last answered (phone clock, 0 = never)
    private var noneSince = 0L       // since when there has been no session at all (elapsedRealtime, 0 = there is one)
    private var pending: String? = null
    private var pendingAt = 0L

    /** The Halo screen is in front (the bar hides, the big timer is right there). */
    @Volatile var haloShown = false

    /** The running MainActivity, while it exists: its page is the one that can press the timer's buttons. */
    @Volatile var page: MainActivity? = null

    private const val REMOTE_KEEP_MS = 10L * 60_000L        // can't reach the server? keep showing the last answer this long (the bar counts by itself)
    private const val SILENT_END_MS = 180_000L              // the page has been quiet this long AND the server says nothing is running: the session is over

    /* ---------------- from the server (service) ---------------- */
    fun onServer(c: Context, res: Net.StateResult) {
        val now = System.currentTimeMillis()
        serverOkAt = now
        remote = res.row?.let { Remote(it, now) }
        changed(c)
    }

    /* ---------------- from the Halo page (MainActivity) ---------------- */
    fun onPageSnap(c: Context, json: String?) {
        if (json != null && json.length > 20_000) return      // never store something unreasonable
        Prefs.setMiniSnap(c, json)
        if (json != null) {
            try {
                val o = JSONObject(json)
                val pc = o.optString("pc", "")
                if (o.optString("phase", "focus") == "focus" && Regex("^#[0-9a-fA-F]{6}$").matches(pc)) Prefs.setMiniAccent(c, pc)
            } catch (e: Exception) { }
        }
        flushPending()
        changed(c)
    }

    fun pageMsg(text: String) {
        val t = text.take(80)
        if (t.isNotEmpty()) BlockerService.instance?.miniMsg(t)
    }

    /** The person asked for the bar back (from Halo's own pop-out button). */
    fun show(c: Context) {
        Prefs.setMiniDismissed(c, false)
        changed(c)
    }

    /** Dragged onto the cross: hide it for this session. The timer itself keeps running. */
    fun dismiss(c: Context) {
        Prefs.setMiniDismissed(c, true)
        changed(c)
    }

    /** Tell the service something changed, so it redraws now instead of at its next look. */
    fun changed(c: Context) {
        try { BlockerService.instance?.miniSync() } catch (e: Exception) { }
    }

    /** Called by the service on every look: once nothing has been running for a few seconds, a bar closed with the cross may come back for the next session. */
    fun track(c: Context, hasSession: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (hasSession) { noneSince = 0L; return }
        if (noneSince == 0L) noneSince = now
        else if (now - noneSince > 5_000L && Prefs.miniDismissed(c)) Prefs.setMiniDismissed(c, false)
    }

    /* ---------------- what to draw ---------------- */

    /** The timer picture for the bar as a JSON string, or null when there is no session to show. */
    fun resolve(c: Context): String? {
        val now = System.currentTimeMillis()
        var local: JSONObject? = null
        val lj = Prefs.miniSnap(c)
        if (lj != null) {
            local = try { JSONObject(lj) } catch (e: Exception) { null }
            // The server answered a moment ago and says nothing is running, but the page has been quiet for minutes: the session is over (e.g. the page was closed).
            if (local != null && remote == null && serverOkAt > 0L && now - serverOkAt < 60_000L && Prefs.miniSnapAgeMs(c) > SILENT_END_MS) local = null
        }
        if (local != null) {
            local.put("src", "local")
            return local.toString()
        }
        val r = remote ?: return null
        if (now - r.at > REMOTE_KEEP_MS) return null
        val row = r.row
        val brk = row.mode == "pomo" && row.phase != "focus"
        val color = if (brk) "#3ddc84" else Prefs.miniAccent(c)
        val o = JSONObject()
        o.put("v", 1)
        o.put("src", "remote")
        o.put("label", if (row.label.isEmpty()) "another device" else row.label)
        o.put("mode", row.mode)
        o.put("phase", if (row.phase == "short" || row.phase == "long") row.phase else "focus")
        o.put("over", row.over)
        o.put("running", row.running)
        o.put("total", row.total)
        o.put("base", row.elapsed)      // what the timer showed as counted when the server answered ...
        o.put("startTs", r.at)          // ... plus the time since then (the bar adds that itself while running)
        o.put("pauseAt", 0)
        o.put("dMini", true)
        o.put("pc", color)
        return o.toString()
    }

    /* ---------------- a button pressed on the bar ---------------- */
    private val allowed = setOf("toggle", "finish", "discard", "pick", "swnow", "swall", "poke")

    fun command(c: Context, a: String, id: String) {
        if (a !in allowed) return
        val msg = JSONObject().put("t", "cmd").put("a", a).put("id", id.take(80)).toString()
        val p = page
        if (p != null && p.deliver(msg)) return
        if (a == "poke") return
        // The Halo page is not running (the phone closed it). Open Halo so the press can be done: the timer lives there.
        pending = msg
        pendingAt = SystemClock.elapsedRealtime()
        openHalo(c)
    }

    /** Called when the page is up again (it sent its first timer picture). */
    private fun flushPending() {
        val m = pending ?: return
        if (SystemClock.elapsedRealtime() - pendingAt > 60_000L) { pending = null; return }
        val p = page ?: return
        if (p.deliver(m)) pending = null
    }

    fun openHalo(c: Context) {
        try {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) { }
    }
}
