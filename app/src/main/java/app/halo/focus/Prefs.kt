package app.halo.focus

import android.content.Context
import android.content.SharedPreferences

/** Everything the app remembers lives here, on the phone only. No secrets. */
object Prefs {
    const val ORIGIN = "https://jeegrinder1.netlify.app"
    private const val STALE_MS = 130_000L   // if the Halo page stops reporting for this long, the session counts as over

    private fun sp(c: Context): SharedPreferences = c.getSharedPreferences("halo", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun blocked(c: Context): Set<String> = sp(c).getStringSet("blocked", emptySet()) ?: emptySet()
    fun setSettings(c: Context, enabled: Boolean, blocked: Set<String>) {
        sp(c).edit().putBoolean("enabled", enabled).putStringSet("blocked", HashSet(blocked)).apply()
    }

    fun onboarded(c: Context) = sp(c).getBoolean("onboarded", false)
    fun setOnboarded(c: Context) = sp(c).edit().putBoolean("onboarded", true).apply()

    /** src is "local" (this phone's timer) or "remote" (a session running on another device). */
    fun setSession(c: Context, src: String, on: Boolean) {
        val k = if (src == "remote") "remote" else "local"
        sp(c).edit().putBoolean(k + "_on", on).putLong(k + "_ts", System.currentTimeMillis()).apply()
    }

    private fun live(c: Context, k: String): Boolean {
        val p = sp(c)
        return p.getBoolean(k + "_on", false) && System.currentTimeMillis() - p.getLong(k + "_ts", 0L) < STALE_MS
    }

    fun sessionActive(c: Context) = live(c, "local") || live(c, "remote")

    /** Blocking is wanted at all (switch on and at least one app chosen). */
    fun blockingWanted(c: Context) = enabled(c) && blocked(c).isNotEmpty()
}
