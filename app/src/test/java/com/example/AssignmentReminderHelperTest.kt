package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.example.notifications.AssignmentReminderHelper
import com.example.notifications.AssignmentReminderReceiver
import com.example.notifications.AssignmentReminderSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AssignmentReminderHelperTest {

    private lateinit var app: Application

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun `computeReminderPlan schedules exactly 15 minutes before due timestamp`() {
        val dueCal = Calendar.getInstance().apply {
            set(2026, Calendar.NOVEMBER, 20, 14, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowMs = dueCal.timeInMillis - (60 * 60 * 1000L) // 1 hour prior
        val spec = AssignmentReminderSpec(
            id = "asg_test_1",
            title = "Kuis Basis Data",
            course = "Basis Data",
            dueDateIso = "2026-11-20T14:30",
            status = "pending",
        )

        val plan = AssignmentReminderHelper.computeReminderPlan(spec, nowMs)
        assertNotNull(plan)
        assertEquals(dueCal.timeInMillis, plan!!.dueAtMs)
        assertEquals(dueCal.timeInMillis - 15 * 60 * 1000L, plan.triggerAtMs)
        assertFalse(plan.triggerImmediately)
        assertEquals("14:30 WIB", plan.dueTimeLabel)
    }

    @Test
    fun `computeReminderPlan ignores completed graded and overdue assignments`() {
        val dueMs = AssignmentReminderHelper.parseDueDateTimeMs("2026-11-20")!!
        val oneHourBefore = dueMs - (60 * 60 * 1000L)

        assertNull(
            AssignmentReminderHelper.computeReminderPlan(
                AssignmentReminderSpec("a1", "Selesai", "MK", "2026-11-20", "completed"),
                oneHourBefore
            )
        )
        assertNull(
            AssignmentReminderHelper.computeReminderPlan(
                AssignmentReminderSpec("a2", "Dinilai", "MK", "2026-11-20", "graded"),
                oneHourBefore
            )
        )
        assertNull(
            AssignmentReminderHelper.computeReminderPlan(
                AssignmentReminderSpec("a3", "Telat", "MK", "2026-11-20", "pending"),
                dueMs + 1000L
            )
        )
    }

    @Test
    fun `syncAssignmentReminders schedules AlarmManager and cancels on disable`() {
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val shadowAlarm = Shadows.shadowOf(alarmManager)

        val dueMs = AssignmentReminderHelper.parseDueDateTimeMs("2026-11-25T10:00")!!
        val nowMs = dueMs - (2 * 60 * 60 * 1000L) // 2 hours prior

        val count = AssignmentReminderHelper.syncAssignmentReminders(
            context = app,
            assignments = listOf(
                AssignmentReminderSpec("asg_future", "Makalah AI", "AI", "2026-11-25T10:00", "pending")
            ),
            notificationsEnabled = true,
            nowMs = nowMs,
        )
        assertEquals(1, count)
        assertEquals(1, shadowAlarm.scheduledAlarms.size)
        assertEquals(dueMs - 15 * 60 * 1000L, shadowAlarm.scheduledAlarms.first().triggerAtTime)

        val disabledCount = AssignmentReminderHelper.syncAssignmentReminders(
            context = app,
            assignments = listOf(
                AssignmentReminderSpec("asg_future", "Makalah AI", "AI", "2026-11-25T10:00", "pending")
            ),
            notificationsEnabled = false,
            nowMs = nowMs,
        )
        assertEquals(0, disabledCount)
        assertTrue(shadowAlarm.scheduledAlarms.isEmpty())
    }

    @Test
    fun `receiver posts local notification once and deduplicates repeats`() {
        val notifManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val shadowNotif = Shadows.shadowOf(notifManager)

        val receiver = AssignmentReminderReceiver()
        val intent = Intent().apply {
            putExtra(AssignmentReminderHelper.EXTRA_ASSIGNMENT_ID, "asg_rx_1")
            putExtra(AssignmentReminderHelper.EXTRA_TITLE, "Tugas Algoritma")
            putExtra(AssignmentReminderHelper.EXTRA_COURSE, "Algoritma")
            putExtra(AssignmentReminderHelper.EXTRA_DUE_TIME_LABEL, "09:30 WIB")
            putExtra(AssignmentReminderHelper.EXTRA_DEDUP_KEY, "asg_rx_1_1795426200000")
            putExtra(AssignmentReminderHelper.EXTRA_IS_URGENT, false)
        }

        receiver.onReceive(app, intent)
        assertEquals(1, shadowNotif.allNotifications.size)

        // Repeating the same broadcast with same dedupKey must not duplicate
        receiver.onReceive(app, intent)
        assertEquals(1, shadowNotif.allNotifications.size)
    }
}
