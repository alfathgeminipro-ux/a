package com.example.auth

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider
import com.google.firebase.auth.auth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private const val TAG = "Auth"

fun resolveUserProvider(user: FirebaseUser): String {
    return if (user.providerData.any { it.providerId == "apple.com" }) "apple" else "google"
}

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { auth ->
        trySend(auth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

fun attemptAutoSignIn(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: (FirebaseUser) -> Unit,
    onUnauthenticated: () -> Unit,
    scope: CoroutineScope,
) {
    val existing = Firebase.auth.currentUser
    if (existing != null) {
        onAuthSuccess(existing)
        return
    }
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onUnauthenticated()
        return
    }
    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(true)
        .setServerClientId(clientId)
        .setAutoSelectEnabled(true)
        .build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()
    scope.launch {
        try {
            val cred = credentialManager.getCredential(context, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val result = Firebase.auth
                    .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                    .await()
                val user = result.user
                if (user != null) onAuthSuccess(user) else onUnauthenticated()
            } else {
                onUnauthenticated()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            onUnauthenticated()
        }
    }
}

fun onGoogleSignInClicked(
    context: Context,
    credentialManager: CredentialManager,
    onAuthSuccess: (FirebaseUser) -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {},
) {
    val clientId = try {
        context.getString(R.string.default_web_client_id)
    } catch (_: Exception) {
        onAuthError("Konfigurasi Google Sign-In tidak ditemukan.")
        return
    }
    val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()
    scope.launch {
        try {
            val activity = context as? Activity
                ?: throw IllegalStateException("Context must be an Activity")
            val cred = credentialManager.getCredential(activity, request).credential
            if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                val result = Firebase.auth
                    .signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                    .await()
                val user = result.user
                if (user != null) {
                    onAuthSuccess(user)
                } else {
                    onAuthError("Gagal memuat data akun Google.")
                }
            } else {
                onAuthError("Tipe kredensial tidak dikenali.")
            }
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
            onAuthCancelled()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            onAuthError(e.localizedMessage ?: "Login Google gagal.")
        }
    }
}

fun onAppleSignInClicked(
    context: Context,
    onAuthSuccess: (FirebaseUser) -> Unit,
    onAuthError: (String) -> Unit,
    scope: CoroutineScope,
    onAuthCancelled: () -> Unit = {},
) {
    val activity = context as? Activity
    if (activity == null) {
        onAuthError("Activity tidak tersedia untuk login Apple.")
        return
    }
    scope.launch {
        try {
            val pendingTask = Firebase.auth.pendingAuthResult
            val result = if (pendingTask != null) {
                pendingTask.await()
            } else {
                val provider = OAuthProvider.newBuilder("apple.com")
                    .setScopes(listOf("email", "name"))
                    .addCustomParameter("locale", "id")
                    .build()
                Firebase.auth.startActivityForSignInWithProvider(activity, provider).await()
            }
            val user = result.user
            if (user != null) {
                onAuthSuccess(user)
            } else {
                onAuthError("Gagal memuat data akun Apple.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseAuthException) {
            Log.e(TAG, "Apple Sign-In FirebaseAuthException: ${e.errorCode}", e)
            when (e.errorCode) {
                "ERROR_WEB_CONTEXT_CANCELED",
                "ERROR_USER_CANCELLED" -> onAuthCancelled()
                "ERROR_OPERATION_NOT_ALLOWED" -> onAuthError(
                    "Provider Apple Sign-In belum diaktifkan di Firebase Console (Authentication > Sign-in method > Apple)."
                )
                else -> onAuthError(e.localizedMessage ?: "Login Apple gagal.")
            }
        } catch (e: Exception) {
            val msg = e.localizedMessage.orEmpty()
            if (msg.contains("canceled", ignoreCase = true) || msg.contains("cancelled", ignoreCase = true)) {
                onAuthCancelled()
            } else {
                Log.e(TAG, "Apple Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Login Apple gagal.")
            }
        }
    }
}

fun signOutGoogle(
    credentialManager: CredentialManager,
    scope: CoroutineScope,
    onBeforeSignOut: () -> Unit = {},
    onSignOutComplete: () -> Unit = {},
) {
    scope.launch {
        withContext(NonCancellable) {
            try {
                onBeforeSignOut()
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear credential state: ${e.message}")
            } finally {
                Firebase.auth.signOut()
                onSignOutComplete()
            }
        }
    }
}
