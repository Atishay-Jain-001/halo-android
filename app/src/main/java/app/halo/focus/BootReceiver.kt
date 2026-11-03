package app.halo.focus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After the phone restarts or the app is updated, start the blocker again so it works without opening Halo first. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val a = intent.action
        if (a == Intent.ACTION_BOOT_COMPLETED || a == Intent.ACTION_MY_PACKAGE_REPLACED) BlockerService.ensure(context)
    }
}
