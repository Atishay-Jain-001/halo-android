package app.halo.focus

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** The two tiny questions the app asks the server by itself. Blocking calls: only run them on a background thread. */
object Net {
    class Status(val linked: Boolean, val live: Boolean, val device: String)

    private fun post(link: Prefs.Link, fn: String, body: JSONObject): String? {
        var open: HttpURLConnection? = null
        try {
            val conn = URL(link.url + "/rest/v1/rpc/" + fn).openConnection() as HttpURLConnection
            open = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = 6_000
            conn.readTimeout = 8_000
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("apikey", link.anon)
            if (link.anon.startsWith("eyJ")) conn.setRequestProperty("Authorization", "Bearer " + link.anon)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return null
        } finally {
            try { open?.disconnect() } catch (e: Exception) {}
        }
    }

    /** null = could not reach the server (do not change anything). */
    fun status(link: Prefs.Link): Status? {
        val t = post(link, "android_focus_status", JSONObject().put("p_key", link.key)) ?: return null
        return try {
            val o = JSONObject(t)
            Status(o.optBoolean("linked", false), o.optBoolean("live", false), if (o.isNull("device")) "" else o.optString("device", ""))
        } catch (e: Exception) { null }
    }

    /** Keeps this phone's own session row alive while the Halo page sleeps. true/false = the server's answer, null = could not reach it. */
    fun touch(link: Prefs.Link): Boolean? {
        val t = post(link, "android_focus_touch", JSONObject().put("p_key", link.key).put("p_device", link.device)) ?: return null
        return when (t.trim()) { "true" -> true; "false" -> false; else -> null }
    }
}
