package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val avatarUrl: String? = null,
    val role: String = "student",
    val notificationsEnabled: Boolean = true,
    val seeded: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class Assignment(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val course: String = "",
    val description: String = "",
    val dueDateIso: String = "",
    val status: String = "pending",
    val progress: Long = 0L,
    val teacherName: String = "",
    val attachments: List<String> = emptyList(),
    val submissionNote: String? = null,
    val gradeScore: Long? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class CalendarEvent(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val dateIso: String = "",
    val timeLabel: String = "",
    val colorTag: String = "blue",
    val reminderMinutes: Long = 15L,
    val category: String = "Kuliah",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class MaterialItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val course: String = "",
    val type: String = "pdf",
    val readProgress: Long = 0L,
    val summary: String = "",
    val downloaded: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val iconType: String = "group",
    val unreadCount: Long = 0L,
    val lastMessage: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sender: String = "me",
    val senderName: String = "",
    val text: String = "",
    val timeLabel: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
)
