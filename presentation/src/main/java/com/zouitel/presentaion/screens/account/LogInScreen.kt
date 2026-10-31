package com.zouitel.presentaion.screens.account

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

// Native Firebase (Required for SMS Triggering & Auto-Verification)
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.mmk.kmpauth.google.rememberGoogleSignInState
import com.zouitel.presentaion.model.UserProfile
import com.zouitel.presentaion.screens.MainScreen

// GitLive Firebase (Used for Sign-In Execution)
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.AuthCredential
import dev.gitlive.firebase.auth.auth

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

class LogInScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = koinInject<LogInScreenModel>()

        LaunchedEffect(Unit) {
            val firebaseUser = Firebase.auth.currentUser
            if (firebaseUser != null) {
                // Repair profiles for users who signed in before profile provisioning
                // was added, rather than sending them into the app with a missing record.
                val profileResult = screenModel.saveUserProfile(
                    UserProfile(
                        uid = firebaseUser.uid,
                        fullName = firebaseUser.displayName ?: "",
                        email = firebaseUser.email,
                        phoneNumber = firebaseUser.phoneNumber,
                        photoUrl = firebaseUser.photoURL
                    )
                )
                if (profileResult.e == null) {
                    navigator.replaceAll(MainScreen())
                } else {
                    Toast.makeText(
                        context,
                        profileResult.e?.message ?: "Could not load your account profile",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }

        LogIn(onSignedIn = { navigator.replaceAll(MainScreen()) })
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun LogIn(onSignedIn: () -> Unit) {
        val context = LocalContext.current
        val activity = context as? Activity
        val coroutineScope = rememberCoroutineScope()
        val screenModel = koinInject<LogInScreenModel>()

        var isLoading by remember { mutableStateOf(false) }

        var phoneNumber by remember { mutableStateOf("") }
        var otpCode by remember { mutableStateOf("") }
        var codeWasSent by remember { mutableStateOf(false) }
        var verificationId by remember { mutableStateOf<String?>(null) }
        var resendToken by remember { mutableStateOf<PhoneAuthProvider.ForceResendingToken?>(null) }

        val googleSignInState = rememberGoogleSignInState(
            false,
            isAutoSelectEnabled = true
        ) { result ->
            result.onSuccess { googleUser ->
                coroutineScope.launch {
                    isLoading = true
                    try {
                        val credential = dev.gitlive.firebase.auth.GoogleAuthProvider.credential(
                            idToken = googleUser.idToken,
                            accessToken = null
                        )
                        val authResult = Firebase.auth.signInWithCredential(credential)
                        val firebaseUser = authResult.user
                            ?: throw IllegalStateException("Google sign-in returned no Firebase user.")

                        val profileResult = screenModel.saveUserProfile(
                            UserProfile(
                                uid = firebaseUser.uid,
                                fullName = firebaseUser.displayName ?: "",
                                email = firebaseUser.email,
                                phoneNumber = firebaseUser.phoneNumber,
                                photoUrl = firebaseUser.photoURL
                            )
                        )
                        profileResult.e?.let { throw it }

                        onSignedIn()
                    } catch (e: Exception) {
                        Toast.makeText(context, e.message ?: "Google sign-in failed", Toast.LENGTH_SHORT).show()
                    } finally {
                        isLoading = false
                    }
                }
            }.onFailure { error ->
                Toast.makeText(context, error.message ?: "Google sign-in cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        val phoneCallbacks = remember {
            object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                    signInWithNativeCredential(
                        credential,
                        coroutineScope,
                        context,
                        screenModel,
                        onSignedIn
                    ) { isLoading = it }
                }

                override fun onVerificationFailed(e: FirebaseException) {
                    isLoading = false
                    Toast.makeText(context, e.message ?: "Could not verify this number", Toast.LENGTH_SHORT).show()
                }

                override fun onCodeSent(
                    id: String,
                    token: PhoneAuthProvider.ForceResendingToken
                ) {
                    isLoading = false
                    verificationId = id
                    resendToken = token
                    codeWasSent = true
                }
            }
        }

        fun startPhoneVerification(forceResend: Boolean) {
            val act = activity
            if (act == null) {
                Toast.makeText(context, "Unable to start verification", Toast.LENGTH_SHORT).show()
                return
            }
            if (phoneNumber.length < 8) {
                Toast.makeText(context, "Enter a valid phone number", Toast.LENGTH_SHORT).show()
                return
            }
            isLoading = true
            val builder = PhoneAuthOptions.newBuilder(FirebaseAuth.getInstance())
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(act)
                .setCallbacks(phoneCallbacks)

            if (forceResend) {
                resendToken?.let { builder.setForceResendingToken(it) }
            }

            PhoneAuthProvider.verifyPhoneNumber(builder.build())
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("") } // Clean minimal bar
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // App Brand Header Icon
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.LocalShipping,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = "Get started with RideGo",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Your ultimate ride, food & shopping experience",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(36.dp))

                    // Google Sign-In Elevated Button
                    OutlinedButton(
                        onClick = { googleSignInState.launch() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp),
                        shape = RoundedCornerShape(16.dp),
                        enabled = !isLoading
                    ) {
                        Text(
                            "Continue with Google",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                    }

                    Spacer(Modifier.height(24.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            "  or sign in with phone  ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    Spacer(Modifier.height(24.dp))

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            if (!codeWasSent) {
                                OutlinedTextField(
                                    value = phoneNumber,
                                    onValueChange = { input -> phoneNumber = input.filter { it.isDigit() || it == '+' } },
                                    label = { Text("Phone number") },
                                    placeholder = { Text("+1 555 123 4567") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isLoading
                                )

                                Button(
                                    onClick = { startPhoneVerification(forceResend = false) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isLoading
                                ) {
                                    Text("Send Verification Code")
                                }
                            } else {
                                Text(
                                    text = "Enter the 6-digit code sent to $phoneNumber",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                OutlinedTextField(
                                    value = otpCode,
                                    onValueChange = { input -> if (input.length <= 6) otpCode = input.filter { it.isDigit() } },
                                    label = { Text("OTP code") },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !isLoading
                                )

                                Button(
                                    onClick = {
                                        val id = verificationId
                                        if (id == null) {
                                            Toast.makeText(context, "Code expired, request a new one", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }
                                        if (otpCode.length != 6) {
                                            Toast.makeText(context, "Enter the 6-digit code", Toast.LENGTH_SHORT).show()
                                            return@Button
                                        }

                                        val gitLiveCredential = dev.gitlive.firebase.auth.PhoneAuthProvider().credential(id, otpCode)
                                        signInWithGitLiveCredential(
                                            gitLiveCredential,
                                            coroutineScope,
                                            context,
                                            screenModel,
                                            onSignedIn
                                        ) { isLoading = it }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = !isLoading
                                ) {
                                    Text("Verify & Sign In")
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    TextButton(
                                        onClick = { startPhoneVerification(forceResend = true) },
                                        enabled = !isLoading
                                    ) {
                                        Text("Resend code")
                                    }

                                    TextButton(
                                        onClick = {
                                            codeWasSent = false
                                            otpCode = ""
                                            verificationId = null
                                        },
                                        enabled = !isLoading
                                    ) {
                                        Text("Change number")
                                    }
                                }
                            }
                        }
                    }

                    if (isLoading) {
                        Spacer(Modifier.height(20.dp))
                        CircularProgressIndicator()
                    }
                }
            }
        }
    }

    private fun signInWithGitLiveCredential(
        credential: AuthCredential,
        scope: CoroutineScope,
        context: android.content.Context,
        screenModel: LogInScreenModel,
        onSignedIn: () -> Unit,
        setLoading: (Boolean) -> Unit
    ) {
        scope.launch {
            setLoading(true)
            try {
                val authResult = Firebase.auth.signInWithCredential(credential)
                val firebaseUser = authResult.user
                    ?: throw IllegalStateException("Phone sign-in returned no Firebase user.")
                val profileResult = screenModel.saveUserProfile(
                    UserProfile(
                        uid = firebaseUser.uid,
                        fullName = firebaseUser.displayName ?: "",
                        email = firebaseUser.email,
                        phoneNumber = firebaseUser.phoneNumber,
                        photoUrl = firebaseUser.photoURL
                    )
                )
                profileResult.e?.let { throw it }
                onSignedIn()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Invalid code, try again", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun signInWithNativeCredential(
        credential: PhoneAuthCredential,
        scope: CoroutineScope,
        context: android.content.Context,
        screenModel: LogInScreenModel,
        onSignedIn: () -> Unit,
        setLoading: (Boolean) -> Unit
    ) {
        scope.launch {
            setLoading(true)
            try {
                val authResult = FirebaseAuth.getInstance().signInWithCredential(credential).await()
                val firebaseUser = authResult.user
                    ?: throw IllegalStateException("Phone sign-in returned no Firebase user.")
                val profileResult = screenModel.saveUserProfile(
                    UserProfile(
                        uid = firebaseUser.uid,
                        fullName = firebaseUser.displayName ?: "",
                        email = firebaseUser.email,
                        phoneNumber = firebaseUser.phoneNumber ?: credential.phoneNumber,
                        photoUrl = firebaseUser.photoUrl?.toString()
                    )
                )
                profileResult.e?.let { throw it }
                onSignedIn()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Invalid code, try again", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    companion object {
        const val WEB_CLIENT_ID = "814762435029-63a565o3ub7d4ph32es0vugn5aoa79qs.apps.googleusercontent.com"
    }
}