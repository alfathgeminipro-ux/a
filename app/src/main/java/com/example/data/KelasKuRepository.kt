package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

class KelasKuRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(): String {
        val uid = auth.currentUser?.uid
        if (uid.isNullOrBlank()) {
            throw IllegalStateException("Pengguna harus masuk dengan Google terlebih dahulu.")
        }
        return uid
    }

    private fun sanitizeId(raw: String, fallbackPrefix: String): String {
        val cleaned = raw.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(100)
        return if (cleaned.isNotBlank()) cleaned else "${fallbackPrefix}_${System.currentTimeMillis()}"
    }

    fun observeProfile(userId: String): Flow<UserProfile?> = flow {
        val path = "users/$userId"
        emitAll(
            db.collection("users").document(userId)
                .snapshots()
                .map { snap ->
                    snap.toObject(
                        UserProfile::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val ex = firestoreError ?: (error as? Exception)
                    if (ex != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(ex, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    fun observeAssignments(userId: String): Flow<List<Assignment>> = flow {
        val path = "users/$userId/assignments"
        emitAll(
            db.collection("users").document(userId).collection("assignments")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        Assignment::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val ex = firestoreError ?: (error as? Exception)
                    if (ex != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(ex, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeEvents(userId: String): Flow<List<CalendarEvent>> = flow {
        val path = "users/$userId/events"
        emitAll(
            db.collection("users").document(userId).collection("events")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        CalendarEvent::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val ex = firestoreError ?: (error as? Exception)
                    if (ex != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(ex, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeMaterials(userId: String): Flow<List<MaterialItem>> = flow {
        val path = "users/$userId/materials"
        emitAll(
            db.collection("users").document(userId).collection("materials")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        MaterialItem::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val ex = firestoreError ?: (error as? Exception)
                    if (ex != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(ex, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeConversations(userId: String): Flow<List<Conversation>> = flow {
        val path = "users/$userId/conversations"
        emitAll(
            db.collection("users").document(userId).collection("conversations")
                .snapshots()
                .map { snap ->
                    snap.toObjects(
                        Conversation::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val ex = firestoreError ?: (error as? Exception)
                    if (ex != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(ex, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun ensureProfileAndSeedIfNeeded(
        displayName: String,
        email: String,
        avatarUrl: String?,
        seedPayloadJson: String,
    ): Result<Unit> {
        val uid = auth.currentUser?.uid
        val profilePath = "users/${uid ?: "unauthenticated"}"
        return try {
            val activeUid = requireUserId()
            val userRef = db.collection("users").document(activeUid)
            val snap = userRef.get().await()
            if (!snap.exists()) {
                val safeName = displayName.trim().ifBlank { "Pengguna KelasKu" }.take(80)
                val safeEmail = email.trim().ifBlank { "user@kelasku.id" }.take(150)
                val profileMap = mutableMapOf<String, Any>(
                    "userId" to activeUid,
                    "displayName" to safeName,
                    "email" to safeEmail,
                    "role" to "student",
                    "notificationsEnabled" to true,
                    "seeded" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (!avatarUrl.isNullOrBlank()) {
                    profileMap["avatarUrl"] = avatarUrl.take(500)
                }
                userRef.set(profileMap).await()
                seedCollectionsFromJson(activeUid, seedPayloadJson)
            } else {
                val seeded = snap.getBoolean("seeded") ?: false
                if (!seeded) {
                    seedCollectionsFromJson(activeUid, seedPayloadJson)
                    userRef.update(
                        mapOf(
                            "seeded" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, profilePath)
            Result.failure(e)
        }
    }

    private suspend fun seedCollectionsFromJson(uid: String, seedJsonStr: String) {
        if (seedJsonStr.isBlank()) return
        val root = JSONObject(seedJsonStr)
        val userDoc = db.collection("users").document(uid)

        val assignments = root.optJSONArray("assignments") ?: JSONArray()
        for (i in 0 until assignments.length()) {
            val item = assignments.getJSONObject(i)
            val docId = sanitizeId(item.optString("id"), "asg_$i")
            val attachmentsArr = item.optJSONArray("attachments") ?: JSONArray()
            val attachmentsList = (0 until minOf(attachmentsArr.length(), 5))
                .map { attachmentsArr.getString(it).take(120) }
            val payload = mapOf(
                "userId" to uid,
                "title" to item.optString("title", "Tugas").take(120),
                "course" to item.optString("course", "Umum").take(80),
                "description" to item.optString("description", "").take(1000),
                "dueDateIso" to item.optString("dueDateIso", "2026-11-20").take(30),
                "status" to item.optString("status", "pending").let {
                    if (it in listOf("pending", "submitted", "completed", "graded")) it else "pending"
                },
                "progress" to item.optInt("progress", 0).coerceIn(0, 100),
                "teacherName" to item.optString("teacherName", "Pengajar").take(80),
                "attachments" to attachmentsList,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            userDoc.collection("assignments").document(docId).set(payload).await()
        }

        val events = root.optJSONArray("events") ?: JSONArray()
        for (i in 0 until events.length()) {
            val item = events.getJSONObject(i)
            val docId = sanitizeId(item.optString("id"), "evt_$i")
            val payload = mapOf(
                "userId" to uid,
                "title" to item.optString("title", "Acara").take(120),
                "dateIso" to item.optString("dateIso", "2026-11-18").take(30),
                "timeLabel" to item.optString("timeLabel", "08:00 WIB").take(40),
                "colorTag" to item.optString("colorTag", "blue").let {
                    if (it in listOf("orange", "blue", "green")) it else "blue"
                },
                "reminderMinutes" to item.optInt("reminderMinutes", 15).coerceIn(0, 1440),
                "category" to item.optString("category", "Kuliah").take(40),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            userDoc.collection("events").document(docId).set(payload).await()
        }

        val materials = root.optJSONArray("materials") ?: JSONArray()
        for (i in 0 until materials.length()) {
            val item = materials.getJSONObject(i)
            val docId = sanitizeId(item.optString("id"), "mat_$i")
            val payload = mapOf(
                "userId" to uid,
                "title" to item.optString("title", "Materi").take(120),
                "course" to item.optString("course", "Umum").take(80),
                "type" to item.optString("type", "pdf").let {
                    if (it in listOf("pdf", "video", "link")) it else "pdf"
                },
                "readProgress" to item.optInt("readProgress", 0).coerceIn(0, 100),
                "summary" to item.optString("summary", "").take(1000),
                "downloaded" to item.optBoolean("downloaded", false),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            userDoc.collection("materials").document(docId).set(payload).await()
        }

        val conversations = root.optJSONArray("conversations") ?: JSONArray()
        for (i in 0 until conversations.length()) {
            val item = conversations.getJSONObject(i)
            val convId = sanitizeId(item.optString("id"), "conv_$i")
            val convRef = userDoc.collection("conversations").document(convId)
            val payload = mapOf(
                "userId" to uid,
                "title" to item.optString("title", "Diskusi").take(100),
                "subtitle" to item.optString("subtitle", "Kelas").take(100),
                "iconType" to item.optString("iconType", "group").take(30),
                "unreadCount" to item.optInt("unreadCount", 0).coerceIn(0, 999),
                "lastMessage" to item.optString("lastMessage", "").take(300),
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            convRef.set(payload).await()

            val messages = item.optJSONArray("messages") ?: JSONArray()
            for (m in 0 until messages.length()) {
                val msg = messages.getJSONObject(m)
                val msgId = sanitizeId(msg.optString("id"), "msg_${i}_$m")
                val msgPayload = mapOf(
                    "userId" to uid,
                    "sender" to msg.optString("sender", "other").let {
                        if (it in listOf("me", "other")) it else "other"
                    },
                    "senderName" to msg.optString("senderName", "Pengajar").take(80),
                    "text" to msg.optString("text", "...").ifBlank { "..." }.take(1000),
                    "timeLabel" to msg.optString("timeLabel", "08:00").take(30),
                    "createdAt" to FieldValue.serverTimestamp(),
                )
                convRef.collection("messages").document(msgId).set(msgPayload).await()
            }
        }
    }

    suspend fun updateRole(role: String): Result<Unit> {
        val path = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            val safeRole = if (role == "teacher") "teacher" else "student"
            db.collection("users").document(uid)
                .update(mapOf("role" to safeRole, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun updateNotifications(enabled: Boolean): Result<Unit> {
        val path = "users/${auth.currentUser?.uid ?: "unauthenticated"}"
        return try {
            val uid = requireUserId()
            db.collection("users").document(uid)
                .update(mapOf("notificationsEnabled" to enabled, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path)
            Result.failure(e)
        }
    }

    suspend fun saveAssignment(itemJson: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/assignments"
        return try {
            val uid = requireUserId()
            val json = JSONObject(itemJson)
            val docId = sanitizeId(json.optString("id"), "asg")
            val docRef = db.collection("users").document(uid).collection("assignments").document(docId)
            docPath = docRef.path

            val existing = docRef.get().await()
            val title = json.optString("title", "Tugas Baru").trim().ifBlank { "Tugas Baru" }.take(120)
            val course = json.optString("course", "Umum").trim().ifBlank { "Umum" }.take(80)
            val desc = json.optString("description", "").take(1000)
            val due = json.optString("dueDateIso", "2026-11-20").take(30)
            val status = json.optString("status", "pending").let {
                if (it in listOf("pending", "submitted", "completed", "graded")) it else "pending"
            }
            val progress = json.optInt("progress", 0).coerceIn(0, 100)
            val teacherName = json.optString("teacherName", "Pengajar").trim().ifBlank { "Pengajar" }.take(80)
            val submissionNote = json.optString("submissionNote", "").take(500).ifBlank { null }
            val gradeScore = if (json.has("gradeScore") && !json.isNull("gradeScore")) {
                json.optInt("gradeScore", 0).coerceIn(0, 100)
            } else null

            val attachmentsArr = json.optJSONArray("attachments") ?: JSONArray()
            val attachmentsList = (0 until minOf(attachmentsArr.length(), 5))
                .map { attachmentsArr.getString(it).take(120) }

            if (existing.exists()) {
                val updates = mutableMapOf<String, Any>(
                    "title" to title,
                    "course" to course,
                    "description" to desc,
                    "dueDateIso" to due,
                    "status" to status,
                    "progress" to progress,
                    "attachments" to attachmentsList,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (submissionNote != null) updates["submissionNote"] = submissionNote
                if (gradeScore != null) updates["gradeScore"] = gradeScore
                docRef.update(updates).await()
            } else {
                val payload = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "title" to title,
                    "course" to course,
                    "description" to desc,
                    "dueDateIso" to due,
                    "status" to status,
                    "progress" to progress,
                    "teacherName" to teacherName,
                    "attachments" to attachmentsList,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                if (submissionNote != null) payload["submissionNote"] = submissionNote
                if (gradeScore != null) payload["gradeScore"] = gradeScore
                docRef.set(payload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteAssignment(assignmentId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/assignments/$assignmentId"
        return try {
            val uid = requireUserId()
            val docId = sanitizeId(assignmentId, "asg")
            val docRef = db.collection("users").document(uid).collection("assignments").document(docId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveEvent(itemJson: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/events"
        return try {
            val uid = requireUserId()
            val json = JSONObject(itemJson)
            val docId = sanitizeId(json.optString("id"), "evt")
            val docRef = db.collection("users").document(uid).collection("events").document(docId)
            docPath = docRef.path
            val existing = docRef.get().await()
            val title = json.optString("title", "Acara Baru").trim().ifBlank { "Acara Baru" }.take(120)
            val dateIso = json.optString("dateIso", "2026-11-18").take(30)
            val timeLabel = json.optString("timeLabel", "09:00 WIB").trim().ifBlank { "09:00 WIB" }.take(40)
            val colorTag = json.optString("colorTag", "blue").let {
                if (it in listOf("orange", "blue", "green")) it else "blue"
            }
            val reminderMinutes = json.optInt("reminderMinutes", 15).coerceIn(0, 1440)
            val category = json.optString("category", "Kuliah").trim().ifBlank { "Kuliah" }.take(40)

            if (existing.exists()) {
                docRef.update(
                    mapOf(
                        "title" to title,
                        "dateIso" to dateIso,
                        "timeLabel" to timeLabel,
                        "colorTag" to colorTag,
                        "reminderMinutes" to reminderMinutes,
                        "category" to category,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                docRef.set(
                    mapOf(
                        "userId" to uid,
                        "title" to title,
                        "dateIso" to dateIso,
                        "timeLabel" to timeLabel,
                        "colorTag" to colorTag,
                        "reminderMinutes" to reminderMinutes,
                        "category" to category,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteEvent(eventId: String): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/events/$eventId"
        return try {
            val uid = requireUserId()
            val docId = sanitizeId(eventId, "evt")
            val docRef = db.collection("users").document(uid).collection("events").document(docId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateMaterial(materialId: String, readProgress: Int, downloaded: Boolean): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/materials/$materialId"
        return try {
            val uid = requireUserId()
            val docId = sanitizeId(materialId, "mat")
            val docRef = db.collection("users").document(uid).collection("materials").document(docId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "readProgress" to readProgress.coerceIn(0, 100),
                    "downloaded" to downloaded,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveConversationAndMessage(convJson: String, msgJson: String?): Result<Unit> {
        var docPath = "users/${auth.currentUser?.uid ?: "unauthenticated"}/conversations"
        return try {
            val uid = requireUserId()
            val conv = JSONObject(convJson)
            val convId = sanitizeId(conv.optString("id"), "conv")
            val convRef = db.collection("users").document(uid).collection("conversations").document(convId)
            docPath = convRef.path

            val existing = convRef.get().await()
            val lastMessage = conv.optString("lastMessage", "").take(300)
            val unreadCount = conv.optInt("unreadCount", 0).coerceIn(0, 999)
            if (existing.exists()) {
                convRef.update(
                    mapOf(
                        "unreadCount" to unreadCount,
                        "lastMessage" to lastMessage,
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            } else {
                convRef.set(
                    mapOf(
                        "userId" to uid,
                        "title" to conv.optString("title", "Percakapan").trim().ifBlank { "Percakapan" }.take(100),
                        "subtitle" to conv.optString("subtitle", "Kelas").trim().ifBlank { "Kelas" }.take(100),
                        "iconType" to conv.optString("iconType", "group").take(30),
                        "unreadCount" to unreadCount,
                        "lastMessage" to lastMessage,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }

            if (!msgJson.isNullOrBlank()) {
                val msg = JSONObject(msgJson)
                val msgId = sanitizeId(msg.optString("id"), "msg")
                val msgRef = convRef.collection("messages").document(msgId)
                docPath = msgRef.path
                msgRef.set(
                    mapOf(
                        "userId" to uid,
                        "sender" to msg.optString("sender", "me").let {
                            if (it in listOf("me", "other")) it else "me"
                        },
                        "senderName" to msg.optString("senderName", "Saya").trim().ifBlank { "Saya" }.take(80),
                        "text" to msg.optString("text", "...").ifBlank { "..." }.take(1000),
                        "timeLabel" to msg.optString("timeLabel", "09:00").take(30),
                        "createdAt" to FieldValue.serverTimestamp(),
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, docPath)
            Result.failure(e)
        }
    }

    suspend fun fetchFullUserStateJson(userId: String): Result<String> {
        val basePath = "users/$userId"
        return try {
            val uid = requireUserId()
            check(uid == userId) { "UID mismatch during snapshot fetch." }
            val userRef = db.collection("users").document(uid)
            val profileSnap = userRef.get().await()
            val profileObj = JSONObject().apply {
                put("uid", uid)
                put("displayName", profileSnap.getString("displayName") ?: auth.currentUser?.displayName ?: "Pengguna")
                put("email", profileSnap.getString("email") ?: auth.currentUser?.email ?: "")
                put("avatarUrl", profileSnap.getString("avatarUrl") ?: auth.currentUser?.photoUrl?.toString() ?: "")
                put("role", profileSnap.getString("role") ?: "student")
                put("notificationsEnabled", profileSnap.getBoolean("notificationsEnabled") ?: true)
            }

            val assignmentsArr = JSONArray()
            val asgSnaps = userRef.collection("assignments").get().await()
            for (doc in asgSnaps.documents) {
                val data = doc.data ?: continue
                assignmentsArr.put(JSONObject().apply {
                    put("id", doc.id)
                    put("title", data["title"] as? String ?: "")
                    put("course", data["course"] as? String ?: "")
                    put("description", data["description"] as? String ?: "")
                    put("dueDateIso", data["dueDateIso"] as? String ?: "")
                    put("status", data["status"] as? String ?: "pending")
                    put("progress", (data["progress"] as? Number)?.toInt() ?: 0)
                    put("teacherName", data["teacherName"] as? String ?: "")
                    put("attachments", JSONArray((data["attachments"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList<String>()))
                    put("submissionNote", data["submissionNote"] as? String ?: "")
                    val score = (data["gradeScore"] as? Number)?.toInt()
                    if (score != null) put("gradeScore", score)
                })
            }

            val eventsArr = JSONArray()
            val evtSnaps = userRef.collection("events").get().await()
            for (doc in evtSnaps.documents) {
                val data = doc.data ?: continue
                eventsArr.put(JSONObject().apply {
                    put("id", doc.id)
                    put("title", data["title"] as? String ?: "")
                    put("dateIso", data["dateIso"] as? String ?: "")
                    put("timeLabel", data["timeLabel"] as? String ?: "")
                    put("colorTag", data["colorTag"] as? String ?: "blue")
                    put("reminderMinutes", (data["reminderMinutes"] as? Number)?.toInt() ?: 15)
                    put("category", data["category"] as? String ?: "Kuliah")
                })
            }

            val materialsArr = JSONArray()
            val matSnaps = userRef.collection("materials").get().await()
            for (doc in matSnaps.documents) {
                val data = doc.data ?: continue
                materialsArr.put(JSONObject().apply {
                    put("id", doc.id)
                    put("title", data["title"] as? String ?: "")
                    put("course", data["course"] as? String ?: "")
                    put("type", data["type"] as? String ?: "pdf")
                    put("readProgress", (data["readProgress"] as? Number)?.toInt() ?: 0)
                    put("summary", data["summary"] as? String ?: "")
                    put("downloaded", data["downloaded"] as? Boolean ?: false)
                })
            }

            val convsArr = JSONArray()
            val convSnaps = userRef.collection("conversations").get().await()
            for (doc in convSnaps.documents) {
                val data = doc.data ?: continue
                val msgsArr = JSONArray()
                val msgSnaps = doc.reference.collection("messages").orderBy("createdAt").get().await()
                for (mDoc in msgSnaps.documents) {
                    val mData = mDoc.data ?: continue
                    msgsArr.put(JSONObject().apply {
                        put("id", mDoc.id)
                        put("sender", mData["sender"] as? String ?: "other")
                        put("senderName", mData["senderName"] as? String ?: "")
                        put("text", mData["text"] as? String ?: "")
                        put("timeLabel", mData["timeLabel"] as? String ?: "")
                    })
                }
                convsArr.put(JSONObject().apply {
                    put("id", doc.id)
                    put("title", data["title"] as? String ?: "")
                    put("subtitle", data["subtitle"] as? String ?: "")
                    put("iconType", data["iconType"] as? String ?: "group")
                    put("unreadCount", (data["unreadCount"] as? Number)?.toInt() ?: 0)
                    put("lastMessage", data["lastMessage"] as? String ?: "")
                    put("messages", msgsArr)
                })
            }

            val payload = JSONObject().apply {
                put("profile", profileObj)
                put("assignments", assignmentsArr)
                put("events", eventsArr)
                put("materials", materialsArr)
                put("conversations", convsArr)
            }
            Result.success(payload.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, basePath)
            Result.failure(e)
        }
    }
}
