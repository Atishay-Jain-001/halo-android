package app.halo.focus

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The first-open walkthrough (also reachable from Halo's settings card). Built in code, one step at a time. */
class SetupActivity : Activity() {
    private lateinit var box: LinearLayout
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F1220")) }
        box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (36 * dp).toInt(), (20 * dp).toInt(), (40 * dp).toInt())
        }
        sv.addView(box)
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun text(t: String, size: Float, color: String, bold: Boolean = false, top: Int = 0) = TextView(this).apply {
        text = t; textSize = size; setTextColor(Color.parseColor(color)); setPadding(0, (top * dp).toInt(), 0, 0)
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun button(t: String, filled: Boolean = true, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setTextColor(Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = 12 * dp
            if (filled) setColor(Color.parseColor("#7C8CFF")) else { setColor(Color.TRANSPARENT); setStroke((2 * dp).toInt(), Color.parseColor("#7C8CFF")) }
        }
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (48 * dp).toInt()).apply { topMargin = (10 * dp).toInt() }
    }

    private fun step(n: String, title: String, ok: Boolean?, body: String, vararg btns: Pair<String, () -> Unit>) {
        val status = when (ok) { true -> "  ✓ Done"; false -> ""; null -> "" }
        box.addView(text("$n. $title$status", 18f, if (ok == true) "#3DDC84" else "#FFFFFF", true, 22))
        box.addView(text(body, 14f, "#C9CEE8", false, 6))
        for ((label, fn) in btns) box.addView(button(label, ok != true, fn))
    }

    /** Tries the "auto-start" screens of the common phone makers one by one; falls back to Halo's own app info. */
    private fun openAutoStart() {
        val tries = listOf(
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
            "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
            "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity"
        )
        for ((pkg, cls) in tries) {
            try {
                startActivity(Intent().setClassName(pkg, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) { /* not this phone: try the next one */ }
        }
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) {}
    }

    private fun build() {
        box.removeAllViews()
        box.addView(text("Set up Halo", 28f, "#FFFFFF", true))
        box.addView(text("Halo can cover distracting apps with a \"Stay focused\" screen while a focus session runs. It needs a few permissions. Do the steps in order. Each time you come back here, the step shows a green ✓ when it worked.", 15f, "#C9CEE8", false, 8))

        if (Build.VERSION.SDK_INT >= 33) {
            step("1", "Allow restricted settings (Android 13 and newer)", null,
                "Because Halo was installed from a file, Android greys out the two permissions below until you allow this.\n\n" +
                    "Tap the button, then on the screen that opens tap the ⋮ (three dots) at the top right and choose \"Allow restricted settings\". If you don't see the ⋮, skip this step; your phone doesn't need it yet. If steps 2 or 3 are greyed out later, come back and do this.",
                "Open Halo's app info" to {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
        }
        step("2", "Display over other apps", Perms.overlay(this),
            "This lets Halo put the \"Stay focused\" screen on top of a blocked app. Tap the button, find Halo, and switch \"Allow display over other apps\" on.",
            "Open this setting" to {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            })
        step("3", "Usage access", Perms.usage(this),
            "This lets Halo see which app is in front, only so it knows when a blocked app is opened. Tap the button, tap Halo in the list, and switch \"Allow usage access\" on.",
            "Open this setting" to { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })

        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            step("4", "Notifications", ok,
                "Android needs Halo to show a small notification while the blocker is ready. It stays quiet.",
                "Allow notifications" to { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) })
        }

        val pmgr = getSystemService(POWER_SERVICE) as PowerManager
        val battOk = pmgr.isIgnoringBatteryOptimizations(packageName)
        step("5", "Stop battery saver from stopping Halo", battOk,
            "Battery savers sometimes switch the blocker off. Tap the button and choose Allow. Then also do the phone-specific tips below.",
            "Allow in the background" to {
                try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
                catch (e: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            })

        step("6", "Auto-start / background switch (many phones)", null,
            "Xiaomi, Redmi, POCO, Oppo, Realme, Vivo, iQOO, Huawei, Honor, OnePlus and some Samsung phones also have a separate \"auto-start\" or \"background\" switch that decides whether Halo may restart itself. " +
                "Tap the button: Halo tries to open that screen for your phone. Switch Halo ON there. If your phone has no such screen, Halo's app info opens instead; look for Battery or Auto-start there.",
            "Open auto-start settings" to { openAutoStart() })

        box.addView(text("Phone-specific battery tips", 16f, "#FFFFFF", true, 22))
        box.addView(text(
            "• Samsung: Settings → Battery → Background usage limits → Never sleeping apps → add Halo.\n" +
                "• Xiaomi / Redmi / POCO: Settings → Apps → Manage apps → Halo → Autostart ON, and Battery saver → No restrictions.\n" +
                "• Oppo / Realme / OnePlus: Settings → Battery → find Halo → allow background activity and auto-launch.\n" +
                "• Vivo / iQOO: Settings → Battery → Background power consumption → Halo → allow.\n" +
                "• Any phone: in the recent-apps screen, lock Halo (long-press its card → Lock) so \"Clear all\" doesn't close it.\n" +
                "• Keep the blocker notification visible; don't swipe the app away from Settings → Apps → Force stop.",
            14f, "#C9CEE8", false, 6))

        box.addView(text("How it works", 16f, "#FFFFFF", true, 22))
        box.addView(text("You never need to open Halo to start blocking: after the first login, Halo keeps checking on its own (even after you swipe it away or restart the phone) and covers your chosen apps as soon as a focus timer runs on your laptop or phone. Pick your blocked apps in Halo's Focus Timer → settings (⚙) → \"Block distracting apps\". Blocking starts by itself whenever a focus timer is running on this phone or on your laptop.", 14f, "#C9CEE8", false, 6))

        box.addView(button("Done") { Prefs.setOnboarded(this); finish() }.also { (it.layoutParams as LinearLayout.LayoutParams).topMargin = (28 * dp).toInt() })
    }
}
