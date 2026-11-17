package com.example.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs

data class AssignmentReminderSpec(
    val id: String,
    val title: String,
    val course: String,
    val dueDateIso: String,
    val status: String,
)

data class ReminderSchedulePlan(
    val assignmentId: String,
    val dueAtMs: Long,
    val triggerAtMs: Long,
    val triggerImmediately: Boolean,
    val dueTimeLabel: String,
)

object AssignmentReminderHelper {
    const val CHANNEL_ID = "kelasku_assignment_reminders"
    const val EXTRA_ASSIGNMENT_ID = "extra_assignment_id"
    const val EXTRA_TITLE = "extra_title"
    const val EXTRA_COURSE = "extra_course"
    const val EXTRA_DUE_TIME_LABEL = "extra_due_time_label"
    const val EXTRA_DEDUP_KEY = "extra_dedup_key"
    const val EXTRA_IS_URGENT = "extra_is_urgent"
    const val REMINDER_LEAD_MS = 15L * 60L * 1000L

    private const val PREFS_NAME = "kelasku_assignment_reminders"
    private const val KEY_TRACKED_IDS = "tracked_assignment_ids"
    private const val KEY_NOTIFIED_KEYS = "notified_dedup_keys"

    fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Pengingat Tenggat Tugas",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Notifikasi pengingat 15 menit sebelum tenggat pengumpulan tugas."
                    enableVibration(true)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun hasNotificationPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * Parses `dueDateIso` formatted as `YYYY-MM-DD` (defaults to 23:59 local time)
     * or `YYYY-MM-DDTHH:mm` (or `YYYY-MM-DD HH:mm`). Returns epoch milliseconds or null.
     */
    fun parseDueDateTimeMs(dueDateIso: String): Long? {
        val trimmed = dueDateIso.trim()
        if (trimmed.length < 10) return null
        val match = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?""").find(trimmed)
            ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].toIntOrNull() ?: 23
        val minute = match.groupValues[5].toIntOrNull() ?: 59

        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    fun computeReminderPlan(
        spec: AssignmentReminderSpec,
        nowMs: Long = System.currentTimeMillis(),
    ): ReminderSchedulePlan? {
        if (spec.id.isBlank()) return null
        if (spec.status == "completed" || spec.status == "graded") return null

        val dueAtMs = parseDueDateTimeMs(spec.dueDateIso) ?: return null
        if (dueAtMs <= nowMs) return null

        val triggerAtMs = dueAtMs - REMINDER_LEAD_MS
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val dueTimeLabel = "${fmt.format(dueAtMs)} WIB"

        return ReminderSchedulePlan(
            assignmentId = spec.id,
            dueAtMs = dueAtMs,
            triggerAtMs = maxOf(nowMs, triggerAtMs),
            triggerImmediately = nowMs >= triggerAtMs,
            dueTimeLabel = dueTimeLabel,
        )
    }

    fun syncAssignmentsFromJson(
        context: Context,
        assignmentsJson: String,
        notificationsEnabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val list = parseAssignmentsJson(assignmentsJson)
        return syncAssignmentReminders(context, list, notificationsEnabled, nowMs)
    }

    fun syncAssignmentReminders(
        context: Context,
        assignments: List<AssignmentReminderSpec>,
        notificationsEnabled: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        ensureNotificationChannel(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val previouslyTracked = prefs.getStringSet(KEY_TRACKED_IDS, emptySet())?.toSet() ?: emptySet()
        val notifiedKeys = prefs.getStringSet(KEY_NOTIFIED_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()

        if (!notificationsEnabled) {
            previouslyTracked.forEach { cancelReminderAlarm(context, alarmManager, it) }
            prefs.edit().putStringSet(KEY_TRACKED_IDS, emptySet()).apply()
            return 0
        }

        val activeIds = mutableSetOf<String>()
        var scheduledCount = 0

        for (spec in assignments) {
            val plan = computeReminderPlan(spec, nowMs)
            if (plan == null) {
                cancelReminderAlarm(context, alarmManager, spec.id)
                continue
            }

            val dedupKey = "${spec.id}_${plan.dueAtMs}"
            if (notifiedKeys.contains(dedupKey)) {
                cancelReminderAlarm(context, alarmManager, spec.id)
                continue
            }

            activeIds.add(spec.id)
            scheduledCount++

            if (plan.triggerImmediately) {
                cancelReminderAlarm(context, alarmManager, spec.id)
                if (hasNotificationPermission(context)) {
                    markDedupKeyNotified(context, dedupKey)
                    notifiedKeys.add(dedupKey)
                    showAssignmentNotification(
                        context = context,
                        assignmentId = spec.id,
                        title = spec.title,
                        course = spec.course,
                        dueTimeLabel = plan.dueTimeLabel,
                        isUrgentWindow = true,
                    )
                }
            } else {
                val pendingIntent = buildReminderPendingIntent(
                    context = context,
                    assignmentId = spec.id,
                    title = spec.title,
                    course = spec.course,
                    dueTimeLabel = plan.dueTimeLabel,
                    dedupKey = dedupKey,
                    isUrgentWindow = false,
                )
                scheduleAlarm(alarmManager, plan.triggerAtMs, pendingIntent)
            }
        }

        // Cancel alarms for removed or newly completed tasks
        (previouslyTracked - activeIds).forEach { removedId ->
            cancelReminderAlarm(context, alarmManager, removedId)
        }
        prefs.edit().putStringSet(KEY_TRACKED_IDS, activeIds).apply()
        return scheduledCount
    }

    fun isDedupKeyNotified(context: Context, dedupKey: String): Boolean {
        if (dedupKey.isBlank()) return false
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_NOTIFIED_KEYS, emptySet())?.contains(dedupKey) == true
    }

    fun markDedupKeyNotified(context: Context, dedupKey: String) {
        if (dedupKey.isBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = prefs.getStringSet(KEY_NOTIFIED_KEYS, emptySet())?.toMutableSet() ?: mutableSetOf()
        updated.add(dedupKey)
        // Keep bounded history
        val trimmed = if (updated.size > 200) updated.toList().takeLast(200).toSet() else updated
        prefs.edit().putStringSet(KEY_NOTIFIED_KEYS, trimmed).apply()
    }

    fun showAssignmentNotification(
        context: Context,
        assignmentId: String,
        title: String,
        course: String,
        dueTimeLabel: String,
        isUrgentWindow: Boolean,
    ): Boolean {
        if (!hasNotificationPermission(context)) return false
        ensureNotificationChannel(context)

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ASSIGNMENT_ID, assignmentId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            requestCodeFor(assignmentId),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val headline = if (isUrgentWindow) {
            "Tenggat Segera Berakhir (< 15 Menit)"
        } else {
            "Pengingat Tugas: 15 Menit Lagi"
        }
        val body = "$title ($course) • Tenggat pukul $dueTimeLabel. Segera kumpulkan tugasmu!"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_reminder)
            .setContentTitle(headline)
            .setContentText("$title • Tenggat $dueTimeLabel")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentPendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(requestCodeFor(assignmentId), notification)
        return true
    }

    fun showTestNotification(context: Context, nextTaskTitle: String?, nextDueLabel: String?): Boolean {
        if (!hasNotificationPermission(context)) return false
        ensureNotificationChannel(context)
        val title = nextTaskTitle?.takeIf { it.isNotBlank() } ?: "Contoh Tugas KelasKu"
        val timeText = nextDueLabel?.takeIf { it.isNotBlank() } ?: "15 menit lagi"
        return showAssignmentNotification(
            context = context,
            assignmentId = "test_preview",
            title = title,
            course = "Pengingat KelasKu",
            dueTimeLabel = timeText,
            isUrgentWindow = false,
        )
    }

    private fun scheduleAlarm(
        alarmManager: AlarmManager,
        triggerAtMs: Long,
        pendingIntent: PendingIntent,
    ) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }

    private fun cancelReminderAlarm(
        context: Context,
        alarmManager: AlarmManager,
        assignmentId: String,
    ) {
        val intent = Intent(context, AssignmentReminderReceiver::class.java)
        val existing = PendingIntent.getBroadcast(
            context,
            requestCodeFor(assignmentId),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (existing != null) {
            alarmManager.cancel(existing)
            existing.cancel()
        }
    }

    private fun buildReminderPendingIntent(
        context: Context,
        assignmentId: String,
        title: String,
        course: String,
        dueTimeLabel: String,
        dedupKey: String,
        isUrgentWindow: Boolean,
    ): PendingIntent {
        val intent = Intent(context, AssignmentReminderReceiver::class.java).apply {
            putExtra(EXTRA_ASSIGNMENT_ID, assignmentId)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_COURSE, course)
            putExtra(EXTRA_DUE_TIME_LABEL, dueTimeLabel)
            putExtra(EXTRA_DEDUP_KEY, dedupKey)
            putExtra(EXTRA_IS_URGENT, isUrgentWindow)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCodeFor(assignmentId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCodeFor(assignmentId: String): Int =
        abs(assignmentId.hashCode()) % 1_000_000 + 2000

    private fun parseAssignmentsJson(jsonStr: String): List<AssignmentReminderSpec> {
        return try {
            val arr = JSONArray(jsonStr)
            List(arr.length()) { idx ->
                val obj = arr.optJSONObject(idx)
                AssignmentReminderSpec(
                    id = obj?.optString("id", "").orEmpty(),
                    title = obj?.optString("title", "Tugas KelasKu").orEmpty(),
                    course = obj?.optString("course", "Kelas").orEmpty(),
                    dueDateIso = obj?.optString("dueDateIso", "").orEmpty(),
                    status = obj?.optString("status", "pending").orEmpty(),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
