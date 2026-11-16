package app.halo.focus

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

/**
 * Everything the app remembers lives here, on the phone only.
 * The "link key" is the one sensitive value: it is NOT a login. It can only ask the server one question
 * ("is a focus session running on my account?") and it can be revoked by logging out of Halo.
 */
object Prefs {
    const val ORIGIN = "https://halo.atishayjain9112009.workers.dev/"
    private const val STALE_MS = 130_000L                    // old way (page tells the app about another device): only used while the app is NOT linked
    private const val LOCAL_MAX_MS = 12L * 3_600_000L        // a session started on this phone never lasts longer than the Halo timer's own 12 h limit
    private const val SERVER_KEEP_MS = 10L * 60_000L         // can't reach the server? keep trusting the last "a session is running" answer this long (so airplane mode is no way around the block)
    private const val DAY_MS = 26L * 3_600_000L

    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("halo", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun blocked(c: Context): Set<String> = sp(c).getStringSet("blocked", emptySet()) ?: emptySet()
    fun setSettings(c: Context, enabled: Boolean, blocked: Set<String>) {
        sp(c).edit().putBoolean("enabled", enabled).putStringSet("blocked", HashSet(blocked)).apply()
    }

    fun onboarded(c: Context) = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context) = sp(c).edit().putBoolean("onboarded", true).apply()

    /**
     * src is "local" (this phone's own timer) or "remote" (old page-based flag for a session on another device).
     * until (local only): when a Pomodoro focus phase ends, in ms since 1970 (0 = open-ended). It lets the app stop blocking
     * on time even while the Halo page is asleep.
     */
    fun setSession(c: Context, src: String, on: Boolean, until: Long = 0L) {
        val k = if (src == "remote") "remote" else "local"
        val now = System.currentTimeMillis()
        val e = sp(c).edit().putBoolean(k + "_on", on).putLong(k + "_ts", now)
        if (k == "local") {
            val u = if (on && until > 0L && until < now + DAY_MS) until else 0L
            e.putLong("local_until", u)
        }
        e.apply()
    }

    fun setLocalOff(c: Context) {
        sp(c).edit().putBoolean("local_on", false).putLong("local_until", 0L).putLong("local_ts", System.currentTimeMillis()).apply()
    }

    /** A focus session started on THIS phone is running (and has not run past its known end). */
    fun localLive(c: Context): Boolean {
        val p = sp(c)
        val now = System.currentTimeMillis()
        if (!p.getBoolean("local_on", false)) return false
        if (now - p.getLong("local_ts", 0L) > LOCAL_MAX_MS) return false
        val until = p.getLong("local_until", 0L)
        return until == 0L || now < until
    }

    /** How long since the Halo page last said anything about this phone's session. */
    fun localSilentMs(c: Context): Long = System.currentTimeMillis() - sp(c).getLong("local_ts", 0L)

    private fun remoteFlagLive(c: Context): Boolean {
        val p = sp(c)
        return p.getBoolean("remote_on", false) && System.currentTimeMillis() - p.getLong("remote_ts", 0L) < STALE_MS
    }

    /* ---- the link to the server (set by the Halo page after login) ---- */
    class Link(val key: String, val url: String, val anon: String, val device: String)

    fun link(c: Context): Link? {
        val p = sp(c)
        val k = p.getString("link_key", null) ?: return null
        val u = p.getString("link_url", null) ?: return null
        val a = p.getString("link_anon", null) ?: return null
        val d = p.getString("link_device", null) ?: return null
        if (k.isEmpty() || u.isEmpty() || a.isEmpty()) return null
        return Link(k, u, a, d)
    }

    fun linked(c: Context) = link(c) != null
    fun linkUid(c: Context): String = sp(c).getString("link_uid", "") ?: ""
    fun linkDevice(c: Context): String = sp(c).getString("link_device", "") ?: ""

    /** Only a real https Supabase address is accepted, so the key can never be sent anywhere else. */
    private fun goodUrl(url: String): Boolean {
        return try {
            val u = URI(url)
            u.scheme == "https" && u.userInfo == null && u.query == null && u.fragment == null &&
                (u.port == -1 || u.port == 443) && (u.host ?: "").endsWith(".supabase.co") &&
                (u.path ?: "").let { it.isEmpty() || it == "/" }
        } catch (e: Exception) { false }
    }

    fun setLink(c: Context, key: String, url: String, anon: String, device: String, uid: String): Boolean {
        if (key.length < 32 || key.length > 200) return false
        if (!goodUrl(url)) return false
        if (anon.length < 20 || anon.length > 800) return false
        if (device.length < 8 || device.length > 60 || uid.length > 80) return false
        sp(c).edit().putString("link_key", key).putString("link_url", url.trimEnd('/')).putString("link_anon", anon)
            .putString("link_device", device).putString("link_uid", uid)
            .putBoolean("srv_live", false).putString("srv_dev", "").putLong("srv_ts", 0L).apply()
        return true
    }

    /** Log-out (or a revoked key): forget the key and stop treating anything as running. */
    fun clearLink(c: Context) {
        sp(c).edit().remove("link_key").remove("link_url").remove("link_anon").remove("link_device").remove("link_uid")
            .putBoolean("srv_live", false).putString("srv_dev", "").putLong("srv_ts", 0L)
            .putBoolean("local_on", false).putLong("local_until", 0L).putBoolean("remote_on", false)
            .remove("mini_snap").putLong("mini_snap_ts", 0L).putBoolean("mini_dismissed", false).apply()
    }

    /** The server's latest answer to "is a focus session running on my account?". device = which device owns it. */
    fun setServer(c: Context, live: Boolean, device: String) {
        sp(c).edit().putBoolean("srv_live", live).putString("srv_dev", device).putLong("srv_ts", System.currentTimeMillis()).apply()
    }

    /** How long ago the blocker last got an answer from the server (ms), or -1 if it never has. Shown in Halo's settings card. */
    fun serverAgeMs(c: Context): Long {
        val t = sp(c).getLong("srv_ts", 0L)
        return if (t == 0L) -1L else maxOf(0L, System.currentTimeMillis() - t)
    }

    private fun serverLive(c: Context): Boolean {
        val p = sp(c)
        if (!p.getBoolean("srv_live", false)) return false
        if (System.currentTimeMillis() - p.getLong("srv_ts", 0L) > SERVER_KEEP_MS) return false
        val owner = p.getString("srv_dev", "") ?: ""
        return owner.isEmpty() || owner != linkDevice(c)      // a session owned by this phone's own Halo page is covered by the "local" flag
    }

    fun sessionActive(c: Context): Boolean = localLive(c) || (if (linked(c)) serverLive(c) else remoteFlagLive(c))

    /** Blocking is wanted at all (switch on and at least one app chosen). */
    fun blockingWanted(c: Context) = enabled(c) && blocked(c).isNotEmpty()

    /* ---- the floating timer bar (drawn over other apps by MiniOverlay) ---- */
    private const val MINI_SNAP_MAX_MS = 12L * 3_600_000L      // never trust the page's last timer picture for longer than the Halo timer's own 12 h limit

    fun miniOn(c: Context) = sp(c).getBoolean("mini_on", true)
    fun setMiniOn(c: Context, on: Boolean) = sp(c).edit().putBoolean("mini_on", on).apply()

    /** The floating timer is wanted and possible: switched on, linked to the account (so it can ask the server) and allowed to draw over other apps. */
    fun miniWanted(c: Context) = miniOn(c) && linked(c) && Perms.overlay(c)

    /** Something wants the background service kept alive (the watchdog uses this): app blocking, or the floating timer. */
    fun keepAliveWanted(c: Context) = blockingWanted(c) || (miniOn(c) && linked(c))

    /** The service has a reason to run right now. */
    fun serviceWanted(c: Context) = (blockingWanted(c) && Perms.both(c)) || miniWanted(c)

    /** The timer of THIS phone's Halo page as plain data (JSON). Written on every message from the page, which doubles as its heartbeat. null = the page says there is no session. */
    fun setMiniSnap(c: Context, json: String?) {
        val p = sp(c)
        val e = p.edit()
        if (json == null) e.remove("mini_snap").putLong("mini_snap_ts", 0L)
        else {
            if (p.getString("mini_snap", null) != json) e.putString("mini_snap", json)
            e.putLong("mini_snap_ts", System.currentTimeMillis())
        }
        e.apply()
    }

    fun miniSnap(c: Context): String? {
        val p = sp(c)
        val j = p.getString("mini_snap", null) ?: return null
        if (System.currentTimeMillis() - p.getLong("mini_snap_ts", 0L) > MINI_SNAP_MAX_MS) return null
        return j
    }

    /** How long since the Halo page last said anything about its timer. */
    fun miniSnapAgeMs(c: Context): Long = System.currentTimeMillis() - sp(c).getLong("mini_snap_ts", 0L)

    /** The person dragged the bar onto the cross: stay hidden until the next session (or until they bring it back from Halo). */
    fun miniDismissed(c: Context) = sp(c).getBoolean("mini_dismissed", false)
    fun setMiniDismissed(c: Context, v: Boolean) = sp(c).edit().putBoolean("mini_dismissed", v).apply()

    /** Where the bar sits: its centre as a fraction of the screen (so it survives a rotation). null = never moved. */
    fun miniPos(c: Context): FloatArray? {
        val p = sp(c)
        if (!p.contains("mini_fx") || !p.contains("mini_fy")) return null
        return floatArrayOf(p.getFloat("mini_fx", 0.5f), p.getFloat("mini_fy", 0.9f))
    }

    fun setMiniPos(c: Context, fx: Float, fy: Float) = sp(c).edit().putFloat("mini_fx", fx).putFloat("mini_fy", fy).apply()

    /** The accent colour of Halo's timer (from the page), used to colour a session shown from another device. */
    fun miniAccent(c: Context): String = sp(c).getString("mini_accent", "#ff6a4d") ?: "#ff6a4d"
    fun setMiniAccent(c: Context, hex: String) = sp(c).edit().putString("mini_accent", hex).apply()
}
