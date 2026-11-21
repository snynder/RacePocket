package com.racepocket.app.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.workDataOf
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.racepocket.app.MainActivity
import com.racepocket.app.R
import com.racepocket.app.data.FreshFetch
import com.racepocket.app.data.Jolpica
import com.racepocket.app.data.OpenF1
import com.racepocket.app.data.Race
import com.racepocket.app.data.Repo
import com.racepocket.app.data.SessionKind
import com.racepocket.app.data.SessionTime
import com.racepocket.app.ui.Routes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.Year
import java.util.concurrent.TimeUnit

object Notifier {
    const val CHANNEL = "sessions"
    const val CHANNEL_RESULTS = "results"
    private const val PREFS = "alarms"
    private const val RESULT_TAG = "result"
    /** Screen (navigation route) to open when the notification is tapped. */
    const val EXTRA_ROUTE = "route"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, context.getString(R.string.results_notif_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun schedulePeriodic(context: Context) {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork("reschedule", ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<RescheduleWorker>(12, TimeUnit.HOURS).build())
        rescheduleSoon(context)
    }

    fun rescheduleSoon(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("reschedule_now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RescheduleWorker>().build())
    }

    fun sessionLabel(context: Context, kind: SessionKind): String = context.getString(
        when (kind) {
            SessionKind.FP1 -> R.string.s_fp1
            SessionKind.FP2 -> R.string.s_fp2
            SessionKind.FP3 -> R.string.s_fp3
            SessionKind.SPRINT_QUALI -> R.string.s_sprint_quali
            SessionKind.SPRINT -> R.string.s_sprint
            SessionKind.QUALI -> R.string.s_quali
            SessionKind.RACE -> R.string.s_race
        }
    )

    /** Cancels every scheduled alarm and sets them again for the next three weeks. */
    suspend fun reschedule(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getStringSet("ids", emptySet())?.forEach { id ->
            am.cancel(pending(context, id.toInt(), "", ""))
        }
        prefs.edit().putStringSet("ids", emptySet()).apply()

        val s = Repo.settings.current()
        if (!s.resultsEnabled) WorkManager.getInstance(context).cancelAllWorkByTag(RESULT_TAG)
        if (!s.notifEnabled && !s.resultsEnabled) return
        val now = Instant.now()
        val year = Year.now().value
        val races = runCatching { Jolpica.calendar(year) }.getOrDefault(emptyList()) +
            runCatching { if (now.atZone(java.time.ZoneOffset.UTC).monthValue >= 11) Jolpica.calendar(year + 1) else emptyList() }.getOrDefault(emptyList())
        if (s.resultsEnabled) scheduleResults(context, races, s.results, now)
        if (!s.notifEnabled) return
        val ids = mutableSetOf<String>()
        for (race in races) for (session in race.sessions) {
            if (s.notif[session.kind] != true) continue
            val at = session.start.minusSeconds(s.leadMinutes * 60L)
            if (at.isBefore(now) || at.isAfter(now.plusSeconds(21L * 86400))) continue
            val id = (race.season * 1000 + race.round * 10 + session.kind.ordinal)
            val title = "${race.flag} ${race.name}".trim()
            val text = context.getString(R.string.notif_text, sessionLabel(context, session.kind), s.leadMinutes)
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending(context, id, title, text, Routes.LIVE))
            ids += id.toString()
        }
        prefs.edit().putStringSet("ids", ids).apply()
    }

    private fun resultId(season: Int, round: Int, kind: SessionKind) = 1_000_000_000 + season * 1000 + round * 10 + kind.ordinal

    /**
     * One background check per session, from 30 min after its scheduled end. The check repeats (WorkManager
     * backoff) until the result is published, and gives up 30 h after the session.
     */
    private fun scheduleResults(context: Context, races: List<Race>, wanted: Map<SessionKind, Boolean>, now: Instant) {
        val wm = WorkManager.getInstance(context)
        for (race in races) for (session in race.sessions) {
            val end = session.end
            if (end.isBefore(now.minusSeconds(24 * 3600)) || end.isAfter(now.plusSeconds(21L * 86400))) continue
            val name = "result_" + resultId(race.season, race.round, session.kind)
            if (wanted[session.kind] != true) {
                wm.cancelUniqueWork(name)
                continue
            }
            val delayMs = (end.plusSeconds(30 * 60).toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0)
            val req = OneTimeWorkRequestBuilder<ResultWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.MINUTES)
                .addTag(RESULT_TAG)
                .setInputData(
                    workDataOf(
                        "season" to race.season, "round" to race.round, "kind" to session.kind.name,
                        "start" to session.start.toEpochMilli(), "title" to "${race.flag} ${race.name}".trim()
                    )
                )
                .build()
            // KEEP: a check already waiting or retrying keeps its place
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
        }
    }

    /**
     * Top three of a finished session, or null while it is not out yet.
     * Qualifying, sprint and race come from Jolpica, which carries the official classification (with penalties).
     * Practice and sprint qualifying are not in Jolpica, so they come from OpenF1's session result.
     */
    suspend fun top3(season: Int, round: Int, kind: SessionKind, start: Instant): List<String>? = withContext(FreshFetch()) {
        val names: List<String> = when (kind) {
            SessionKind.RACE -> Jolpica.raceResults(season, round).second.filter { it.position != null }.sortedBy { it.position }.map { it.driver.last }
            SessionKind.SPRINT -> Jolpica.sprintResults(season, round).filter { it.position != null }.sortedBy { it.position }.map { it.driver.last }
            SessionKind.QUALI -> Jolpica.qualifying(season, round).sortedBy { it.pos.toIntOrNull() ?: 99 }.map { it.driver.last }
            else -> {
                // the OpenF1 session that starts closest to ours (within 3 h)
                val session = OpenF1.sessionsOfYear(season)
                    .mapNotNull { o -> o.start?.let { st -> o to kotlin.math.abs(st.epochSecond - start.epochSecond) } }
                    .filter { it.second < 3 * 3600 }
                    .minByOrNull { it.second }?.first ?: return@withContext null
                val res = OpenF1.sessionResult(session.key).filter { it.position != null }
                if (res.size < 3) return@withContext null
                val drivers = OpenF1.drivers(session.key).associateBy { it.number }
                res.map { r -> drivers[r.driver]?.lastName?.takeIf { it.isNotBlank() } ?: "#${r.driver}" }
            }
        }
        names.take(3).takeIf { it.size == 3 }
    }

    /** Called by [ResultWorker]: true when it is done (shown, already shown, no longer wanted or too old). */
    suspend fun checkResult(context: Context, season: Int, round: Int, kind: SessionKind, start: Instant, title: String): Boolean {
        val id = resultId(season, round, kind)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = prefs.getStringSet("results_done", emptySet()).orEmpty()
        if (id.toString() in done) return true
        val s = Repo.settings.current()
        if (!s.resultsEnabled || s.results[kind] != true) return true
        if (Instant.now().isAfter(SessionTime(kind, start).end.plusSeconds(30L * 3600))) return true
        val top = try {
            top3(season, round, kind, start)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false
        val list = top.mapIndexed { i, n -> "P${i + 1} $n" }.joinToString(" · ")
        // opens the race page on the tab of this session
        val tab = when (kind) {
            SessionKind.RACE -> 0
            SessionKind.QUALI -> 1
            SessionKind.SPRINT -> 2
            else -> 4 // practice tab (also lists sprint qualifying)
        }
        show(
            context, id, "$title · ${sessionLabel(context, kind)}", context.getString(R.string.results_notif_text, list),
            CHANNEL_RESULTS, Routes.race(season, round, tab)
        )
        prefs.edit().putStringSet("results_done", (done + id.toString()).toList().takeLast(200).toSet()).apply()
        return true
    }

    private fun pending(context: Context, id: Int, title: String, text: String, route: String? = null): PendingIntent {
        val i = Intent(context, SessionAlarmReceiver::class.java).putExtra("id", id).putExtra("title", title).putExtra("text", text)
            .putExtra(EXTRA_ROUTE, route)
        return PendingIntent.getBroadcast(context, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun show(context: Context, id: Int, title: String, text: String, channel: String = CHANNEL, route: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        // one request code per notification, so each keeps its own screen to open
        val open = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_ROUTE, route),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}

class SessionAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifier.show(
            context, intent.getIntExtra("id", 0), intent.getStringExtra("title") ?: "", intent.getStringExtra("text") ?: "",
            route = intent.getStringExtra(Notifier.EXTRA_ROUTE)
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Notifier.rescheduleSoon(context)
    }
}

class RescheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            Notifier.reschedule(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

class ResultWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val d = inputData
        val kind = runCatching { SessionKind.valueOf(d.getString("kind") ?: "") }.getOrNull() ?: return Result.success()
        val done = Notifier.checkResult(
            applicationContext, d.getInt("season", 0), d.getInt("round", 0), kind,
            Instant.ofEpochMilli(d.getLong("start", 0L)), d.getString("title") ?: ""
        )
        return if (done) Result.success() else Result.retry()
    }
}
