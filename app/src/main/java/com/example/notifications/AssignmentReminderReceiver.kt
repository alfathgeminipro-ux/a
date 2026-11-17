package com.example.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AssignmentReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val assignmentId = intent.getStringExtra(AssignmentReminderHelper.EXTRA_ASSIGNMENT_ID) ?: return
        val title = intent.getStringExtra(AssignmentReminderHelper.EXTRA_TITLE) ?: "Tugas KelasKu"
        val course = intent.getStringExtra(AssignmentReminderHelper.EXTRA_COURSE) ?: "Kelas"
        val dueTimeLabel = intent.getStringExtra(AssignmentReminderHelper.EXTRA_DUE_TIME_LABEL) ?: "Segera"
        val dedupKey = intent.getStringExtra(AssignmentReminderHelper.EXTRA_DEDUP_KEY).orEmpty()
        val isUrgent = intent.getBooleanExtra(AssignmentReminderHelper.EXTRA_IS_URGENT, false)

        if (dedupKey.isNotBlank() && AssignmentReminderHelper.isDedupKeyNotified(context, dedupKey)) {
            return
        }
        if (AssignmentReminderHelper.hasNotificationPermission(context)) {
            if (dedupKey.isNotBlank()) {
                AssignmentReminderHelper.markDedupKeyNotified(context, dedupKey)
            }
            AssignmentReminderHelper.showAssignmentNotification(
                context = context,
                assignmentId = assignmentId,
                title = title,
                course = course,
                dueTimeLabel = dueTimeLabel,
                isUrgentWindow = isUrgent,
            )
        }
    }
}
