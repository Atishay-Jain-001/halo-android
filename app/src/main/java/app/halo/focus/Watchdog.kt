package app.halo.focus

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * The "bring the blocker back" safety net. Phones (and the phone makers' battery savers) sometimes stop a background app.
 * Before this, the blocker only came back when you opened Halo. Now two independent timers keep checking, and start the
 * blocker again by themselves whenever it is not running:
 *   1. an alarm that wakes every ~5 minutes (and 1.5 s after the app is swiped away or the service is stopped), and
 *   2. a system job that runs every ~15 minutes, survives a restart of the phone, and also runs when the internet comes back.
 * Neither timer does anything when blocking is switched off (or no apps are chosen) AND the floating timer is off.
 */
object Watchdog {
    private const val JOB_ID = 4101
    private const val ALARM_CODE = 4102
    private const val ALARM_EVERY_MS = 5L * 60_000L

    /** Make sure both timers exist (does nothing, and removes them, when neither blocking nor the floating timer is wanted). */
    fun schedule(c: Context) {
        val ctx = c.applicationContext
        if (!Prefs.keepAliveWanted(ctx)) { cancel(ctx); return }
        scheduleJob(ctx)
        scheduleAlarm(ctx, ALARM_EVERY_MS)
    }

    fun cancel(c: Context) {
        val ctx = c.applicationContext
        try { (ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler).cancel(JOB_ID) } catch (e: Exception) {}
        try { (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(ctx)) } catch (e: Exception) {}
    }

    /** One look: bring the blocker back if it is wanted, and set the next alarm. Called by the alarm and by the job. */
    fun check(c: Context) {
        val ctx = c.applicationContext
        if (!Prefs.keepAliveWanted(ctx)) { cancel(ctx); return }
        scheduleAlarm(ctx, ALARM_EVERY_MS)
        BlockerService.ensure(ctx)
    }

    fun scheduleAlarm(c: Context, delayMs: Long) {
        try {
            val ctx = c.applicationContext
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs, alarmIntent(ctx))
        } catch (e: Exception) { /* never crash because of a timer */ }
    }

    private fun alarmIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, ALARM_CODE, Intent(ctx, WatchdogReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun scheduleJob(ctx: Context) {
        try {
            val js = ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(ctx, WatchdogJob::class.java))
                .setPeriodic(15L * 60_000L)
                .setPersisted(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .build()
            js.schedule(job)
        } catch (e: Exception) { /* ignore */ }
    }
}

/** Woken by the alarm. */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Watchdog.check(context)
    }
}

/** Run by the system every ~15 minutes (and again after a restart of the phone). */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Watchdog.check(applicationContext)
        return false      // nothing left running: the work is only "start the blocker if it is not running"
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
