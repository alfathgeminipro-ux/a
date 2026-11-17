package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.credentials.CredentialManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.auth.attemptAutoSignIn
import com.example.auth.authStateFlow
import com.example.auth.onAppleSignInClicked
import com.example.auth.onGoogleSignInClicked
import com.example.auth.resolveUserProvider
import com.example.auth.signOutGoogle
import com.example.data.KelasKuRepository
import com.example.notifications.AssignmentReminderHelper
import com.example.scan.PdfScanHelper
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val tappedAssignmentIdState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tappedAssignmentIdState.value = intent?.getStringExtra(AssignmentReminderHelper.EXTRA_ASSIGNMENT_ID)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                KelasKuAppRoot(
                    tappedAssignmentId = tappedAssignmentIdState.value,
                    onConsumedTappedAssignment = { tappedAssignmentIdState.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tappedAssignmentIdState.value = intent.getStringExtra(AssignmentReminderHelper.EXTRA_ASSIGNMENT_ID)
    }
}

@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
fun KelasKuAppRoot(
    tappedAssignmentId: String? = null,
    onConsumedTappedAssignment: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val repository = remember(context) { KelasKuRepository(context) }

    val currentUser by remember { Firebase.auth.authStateFlow() }
        .collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var pageLoaded by remember { mutableStateOf(false) }
    var pendingSeedJson by remember { mutableStateOf("") }
    var pendingCameraPageId by remember { mutableStateOf<String?>(null) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var lastReminderPayload by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingTestNotification by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun pushToWeb(script: String) {
        val view = webViewRef ?: return
        view.post { view.evaluateJavascript(script, null) }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val payload = lastReminderPayload
            if (payload != null) {
                scope.launch(Dispatchers.IO) {
                    AssignmentReminderHelper.syncAssignmentsFromJson(context, payload.first, payload.second)
                }
            }
            val pendingTest = pendingTestNotification
            pendingTestNotification = null
            if (pendingTest != null) {
                AssignmentReminderHelper.showTestNotification(context, pendingTest.first, pendingTest.second)
            }
            pushToWeb("window.onNativeNotificationStatus && window.onNativeNotificationStatus(true);")
        } else {
            pendingTestNotification = null
            pushToWeb("window.onNativeNotificationStatus && window.onNativeNotificationStatus(false);")
        }
    }

    val takePictureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val pageId = pendingCameraPageId
        pendingCameraPageId = null
        pendingCameraUri = null
        if (success && pageId != null) {
            scope.launch {
                val thumb = withContext(Dispatchers.IO) {
                    PdfScanHelper.buildThumbnailDataUrl(context, pageId)
                }
                if (thumb != null) {
                    pushToWeb("window.onNativeScanPageCaptured && window.onNativeScanPageCaptured(${JSONObject.quote(pageId)}, ${JSONObject.quote(thumb)});")
                } else {
                    pushToWeb("window.onNativeScanError && window.onNativeScanError(${JSONObject.quote("Gagal memproses foto hasil kamera.")});")
                }
            }
        }
    }

    fun launchCameraCapture() {
        val (pageId, uri) = PdfScanHelper.createCameraOutputUri(context)
        pendingCameraPageId = pageId
        pendingCameraUri = uri
        takePictureLauncher.launch(uri)
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCameraCapture()
        } else {
            pushToWeb("window.onNativeScanError && window.onNativeScanError(${JSONObject.quote("Izin kamera ditolak. Aktifkan izin kamera untuk memindai tugas.")});")
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val pageId = PdfScanHelper.copyPickedUriToCache(context, uri) ?: return@withContext null
                    val thumb = PdfScanHelper.buildThumbnailDataUrl(context, pageId) ?: return@withContext null
                    pageId to thumb
                }
                if (result != null) {
                    pushToWeb("window.onNativeScanPageCaptured && window.onNativeScanPageCaptured(${JSONObject.quote(result.first)}, ${JSONObject.quote(result.second)});")
                } else {
                    pushToWeb("window.onNativeScanError && window.onNativeScanError(${JSONObject.quote("Gagal memuat gambar dari galeri.")});")
                }
            }
        }
    }

    LaunchedEffect(pageLoaded, tappedAssignmentId) {
        if (pageLoaded && !tappedAssignmentId.isNullOrBlank()) {
            pushToWeb("window.onNotificationOpenAssignment && window.onNotificationOpenAssignment(${JSONObject.quote(tappedAssignmentId)});")
            onConsumedTappedAssignment()
        }
    }

    // Attempt silent auto-sign-in once on launch & ensure notification channel
    LaunchedEffect(Unit) {
        AssignmentReminderHelper.ensureNotificationChannel(context)
        attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            onAuthSuccess = { /* AuthStateListener handles session transition */ },
            onUnauthenticated = { /* Stay on login card */ },
            scope = scope
        )
    }

    // Auth-gated Firestore observation & sync: keyed by uid so sign-out/switch cancels old listeners cleanly
    val activeUser = currentUser
    if (activeUser != null && pageLoaded) {
        key(activeUser.uid) {
            LaunchedEffect(activeUser.uid, pendingSeedJson) {
                val uid = activeUser.uid
                val userObj = JSONObject().apply {
                    put("uid", uid)
                    put("displayName", activeUser.displayName ?: "Pengguna KelasKu")
                    put("email", activeUser.email ?: "user@kelasku.id")
                    put("avatarUrl", activeUser.photoUrl?.toString() ?: "")
                    put("provider", resolveUserProvider(activeUser))
                }
                pushToWeb("window.onNativeAuthSuccess && window.onNativeAuthSuccess(${JSONObject.quote(userObj.toString())});")

                repository.ensureProfileAndSeedIfNeeded(
                    displayName = activeUser.displayName ?: "Pengguna KelasKu",
                    email = activeUser.email ?: "user@kelasku.id",
                    avatarUrl = activeUser.photoUrl?.toString(),
                    seedPayloadJson = pendingSeedJson
                )

                // Fetch full initial snapshot including conversation messages
                repository.fetchFullUserStateJson(uid).onSuccess { json ->
                    pushToWeb("window.onFirestoreDataSync && window.onFirestoreDataSync(${JSONObject.quote(json)});")
                }

                // Observe real-time Firestore collection changes while authenticated
                combine(
                    repository.observeProfile(uid),
                    repository.observeAssignments(uid),
                    repository.observeEvents(uid),
                    repository.observeMaterials(uid),
                    repository.observeConversations(uid),
                ) { _, _, _, _, _ -> Unit }
                    .debounce(250L)
                    .collect {
                        repository.fetchFullUserStateJson(uid).onSuccess { json ->
                            pushToWeb("window.onFirestoreDataSync && window.onFirestoreDataSync(${JSONObject.quote(json)});")
                        }
                    }
            }
        }
    }

    val bridge = remember(context, repository, credentialManager) {
        KelasKuJsBridge(
            onRequestGoogleSignIn = { seedJson ->
                pendingSeedJson = seedJson
                onGoogleSignInClicked(
                    context = context,
                    credentialManager = credentialManager,
                    onAuthSuccess = { user: FirebaseUser ->
                        val userObj = JSONObject().apply {
                            put("uid", user.uid)
                            put("displayName", user.displayName ?: "Pengguna KelasKu")
                            put("email", user.email ?: "user@kelasku.id")
                            put("avatarUrl", user.photoUrl?.toString() ?: "")
                            put("provider", resolveUserProvider(user))
                        }
                        pushToWeb("window.onNativeAuthSuccess && window.onNativeAuthSuccess(${JSONObject.quote(userObj.toString())});")
                    },
                    onAuthError = { msg ->
                        pushToWeb("window.onNativeAuthError && window.onNativeAuthError(${JSONObject.quote(msg)});")
                    },
                    scope = scope,
                    onAuthCancelled = {
                        pushToWeb("window.onNativeAuthCancelled && window.onNativeAuthCancelled();")
                    }
                )
            },
            onRequestAppleSignIn = { seedJson ->
                pendingSeedJson = seedJson
                onAppleSignInClicked(
                    context = context,
                    onAuthSuccess = { user: FirebaseUser ->
                        val userObj = JSONObject().apply {
                            put("uid", user.uid)
                            put("displayName", user.displayName ?: "Pengguna KelasKu")
                            put("email", user.email ?: "user@kelasku.id")
                            put("avatarUrl", user.photoUrl?.toString() ?: "")
                            put("provider", resolveUserProvider(user))
                        }
                        pushToWeb("window.onNativeAuthSuccess && window.onNativeAuthSuccess(${JSONObject.quote(userObj.toString())});")
                    },
                    onAuthError = { msg ->
                        pushToWeb("window.onNativeAuthError && window.onNativeAuthError(${JSONObject.quote(msg)});")
                    },
                    scope = scope,
                    onAuthCancelled = {
                        pushToWeb("window.onNativeAuthCancelled && window.onNativeAuthCancelled();")
                    }
                )
            },
            onRequestSignOut = {
                signOutGoogle(
                    credentialManager = credentialManager,
                    scope = scope,
                    onSignOutComplete = {
                        pushToWeb("window.onNativeSignedOut && window.onNativeSignedOut();")
                    }
                )
            },
            repository = repository,
            scope = scope,
            onSyncError = { errMsg ->
                pushToWeb("window.onFirestoreSyncError && window.onFirestoreSyncError(${JSONObject.quote(errMsg)});")
            },
            onSetSeedJson = { seedJson ->
                pendingSeedJson = seedJson
            },
            onCaptureScanPage = {
                scope.launch(Dispatchers.Main) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        launchCameraCapture()
                    } else {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }
            },
            onPickScanGalleryPage = {
                scope.launch(Dispatchers.Main) {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
            },
            onBuildAndAttachScanPdf = { assignmentId, pageIdsJson, enhanceContrast ->
                scope.launch {
                    val pageIds = try {
                        val arr = JSONArray(pageIdsJson)
                        List(arr.length()) { idx -> arr.optString(idx) }.filter { it.isNotBlank() }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val pdfResult = withContext(Dispatchers.IO) {
                        PdfScanHelper.buildMultiPagePdf(context, assignmentId, pageIds, enhanceContrast)?.also {
                            PdfScanHelper.clearCachedPages(context)
                        }
                    }
                    if (pdfResult != null) {
                        val resObj = JSONObject().apply {
                            put("assignmentId", assignmentId)
                            put("fileName", pdfResult.fileName)
                            put("pageCount", pdfResult.pageCount)
                            put("sizeKb", pdfResult.sizeKb)
                        }
                        pushToWeb("window.onNativePdfReady && window.onNativePdfReady(${JSONObject.quote(resObj.toString())});")
                    } else {
                        pushToWeb("window.onNativeScanError && window.onNativeScanError(${JSONObject.quote("Gagal membuat dokumen PDF dari halaman pindaian.")});")
                    }
                }
            },
            onOpenScannedPdf = { fileName ->
                scope.launch(Dispatchers.Main) {
                    val opened = PdfScanHelper.openPdfExternal(context, fileName)
                    if (!opened) {
                        pushToWeb("window.onNativeScanError && window.onNativeScanError(${JSONObject.quote("File PDF belum tersedia di perangkat ini atau tidak ada penampil PDF.")});")
                    }
                }
            },
            onClearScanSession = {
                scope.launch(Dispatchers.IO) {
                    PdfScanHelper.clearCachedPages(context)
                }
            },
            onSyncAssignmentReminders = { assignmentsJson, notificationsEnabled, requestPermission ->
                lastReminderPayload = assignmentsJson to notificationsEnabled
                scope.launch(Dispatchers.IO) {
                    AssignmentReminderHelper.syncAssignmentsFromJson(context, assignmentsJson, notificationsEnabled)
                }
                if (notificationsEnabled && requestPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !AssignmentReminderHelper.hasNotificationPermission(context)) {
                    scope.launch(Dispatchers.Main) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            },
            onHasNotificationPermission = {
                AssignmentReminderHelper.hasNotificationPermission(context)
            },
            onTriggerTestNotification = { title, dueLabel ->
                scope.launch(Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !AssignmentReminderHelper.hasNotificationPermission(context)) {
                        pendingTestNotification = title to dueLabel
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        AssignmentReminderHelper.showTestNotification(context, title, dueLabel)
                        pushToWeb("window.onNativeNotificationStatus && window.onNativeNotificationStatus(true);")
                    }
                }
            }
        )
    }

    BackHandler(enabled = true) {
        val view = webViewRef
        if (view != null) {
            view.evaluateJavascript("(window.kelasKuHandleBack && window.kelasKuHandleBack()) ? 'handled' : 'exit'") { res ->
                if (res != "\"handled\"") {
                    if (view.canGoBack()) {
                        view.goBack()
                    } else {
                        (context as? ComponentActivity)?.finish()
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.destroy()
            webViewRef = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFFBFE3F0), Color(0xFFCFEBB8))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                createKelasKuWebView(
                    context = ctx,
                    bridge = bridge,
                    onPageFinished = { pageLoaded = true }
                ).also { webViewRef = it }
            }
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createKelasKuWebView(
    context: android.content.Context,
    bridge: KelasKuJsBridge,
    onPageFinished: () -> Unit,
): WebView {
    return WebView(context).apply {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        addJavascriptInterface(bridge, "KelasKuBridge")
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                onPageFinished()
            }
        }
        loadUrl("file:///android_asset/index.html")
    }
}

private class KelasKuJsBridge(
    private val onRequestGoogleSignIn: (String) -> Unit,
    private val onRequestAppleSignIn: (String) -> Unit,
    private val onRequestSignOut: () -> Unit,
    private val repository: KelasKuRepository,
    private val scope: CoroutineScope,
    private val onSyncError: (String) -> Unit,
    private val onSetSeedJson: (String) -> Unit,
    private val onCaptureScanPage: () -> Unit,
    private val onPickScanGalleryPage: () -> Unit,
    private val onBuildAndAttachScanPdf: (String, String, Boolean) -> Unit,
    private val onOpenScannedPdf: (String) -> Unit,
    private val onClearScanSession: () -> Unit,
    private val onSyncAssignmentReminders: (String, Boolean, Boolean) -> Unit,
    private val onHasNotificationPermission: () -> Boolean,
    private val onTriggerTestNotification: (String, String) -> Unit,
) {
    @JavascriptInterface
    fun isNativeAndroid(): Boolean = true

    @JavascriptInterface
    fun registerSeedTemplate(seedJson: String) {
        onSetSeedJson(seedJson)
    }

    @JavascriptInterface
    fun signInWithGoogle(seedJson: String) {
        onRequestGoogleSignIn(seedJson)
    }

    @JavascriptInterface
    fun signInWithApple(seedJson: String) {
        onRequestAppleSignIn(seedJson)
    }

    @JavascriptInterface
    fun signOut() {
        onRequestSignOut()
    }

    @JavascriptInterface
    fun captureScanPage() {
        onCaptureScanPage()
    }

    @JavascriptInterface
    fun pickScanGalleryPage() {
        onPickScanGalleryPage()
    }

    @JavascriptInterface
    fun buildAndAttachScanPdf(assignmentId: String, pageIdsJson: String, enhanceContrast: Boolean) {
        onBuildAndAttachScanPdf(assignmentId, pageIdsJson, enhanceContrast)
    }

    @JavascriptInterface
    fun openScannedPdf(fileName: String) {
        onOpenScannedPdf(fileName)
    }

    @JavascriptInterface
    fun clearScanSession() {
        onClearScanSession()
    }

    @JavascriptInterface
    fun syncAssignmentReminders(assignmentsJson: String, notificationsEnabled: Boolean, requestPermissionIfNeeded: Boolean) {
        onSyncAssignmentReminders(assignmentsJson, notificationsEnabled, requestPermissionIfNeeded)
    }

    @JavascriptInterface
    fun hasNotificationPermission(): Boolean = onHasNotificationPermission()

    @JavascriptInterface
    fun triggerTestAssignmentNotification(title: String, dueLabel: String) {
        onTriggerTestNotification(title, dueLabel)
    }

    @JavascriptInterface
    fun updateRole(role: String) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.updateRole(role).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal memperbarui peran.")
            }
        }
    }

    @JavascriptInterface
    fun updateNotifications(enabled: Boolean) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.updateNotifications(enabled).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal memperbarui notifikasi.")
            }
        }
    }

    @JavascriptInterface
    fun saveAssignment(itemJson: String) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.saveAssignment(itemJson).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal menyimpan tugas.")
            }
        }
    }

    @JavascriptInterface
    fun deleteAssignment(assignmentId: String) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.deleteAssignment(assignmentId).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal menghapus tugas.")
            }
        }
    }

    @JavascriptInterface
    fun saveEvent(itemJson: String) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.saveEvent(itemJson).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal menyimpan acara.")
            }
        }
    }

    @JavascriptInterface
    fun deleteEvent(eventId: String) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.deleteEvent(eventId).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal menghapus acara.")
            }
        }
    }

    @JavascriptInterface
    fun updateMaterial(materialId: String, readProgress: Int, downloaded: Boolean) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.updateMaterial(materialId, readProgress, downloaded).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal memperbarui materi.")
            }
        }
    }

    @JavascriptInterface
    fun saveConversationAndMessage(convJson: String, msgJson: String) {
        if (Firebase.auth.currentUser == null) return
        scope.launch {
            repository.saveConversationAndMessage(convJson, msgJson.ifBlank { null }).onFailure {
                onSyncError(it.localizedMessage ?: "Gagal menyimpan pesan.")
            }
        }
    }
}
