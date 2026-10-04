package com.zouitel.presentaion.screens.account

import android.app.Activity
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
import com.zouitel.presentaion.screens.ride.MainScreen

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
        LogIn(onSignedIn = { navigator.replaceAll(MainScreen()) })
    }

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

        // --- Google sign-in ---
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
                        // 2. ONLY CALL THIS ONCE. Remove the duplicate Firebase.auth.signInWithCredential(credential)
                        val authResult = Firebase.auth.signInWithCredential(credential)
                        val firebaseUser = authResult.user

                        if (firebaseUser != null && firebaseUser.uid.isNotBlank()) {
                            val cc = screenModel.saveUserProfile(
                                UserProfile(
                                    uid = firebaseUser.uid,
                                    name = firebaseUser.displayName ?: "",
                                    email = firebaseUser.email ?: "",
                                    phoneNumber = firebaseUser.phoneNumber ?: "",
                                    photoUrl = firebaseUser.photoURL
                                )
                            )

                            cc.e?.let {
                                println("saved user, but couldn't save your profile: ${it.message}")
                            }
                        }

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

        // --- Phone verification callbacks ---
        val phoneCallbacks = remember {
            object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                // Auto-verification returns a native credential, so we must use the native sign-in method
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

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Welcome to RideGo",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(32.dp))

                OutlinedButton(
                    onClick = { googleSignInState.launch() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    enabled = !isLoading
                ) {
                    Text("Sign in with Google")
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    HorizontalDivider(modifier = Modifier.weight(1f))
                    Text("  OR  ", fontSize = 12.sp)
                    HorizontalDivider(modifier = Modifier.weight(1f))
                }

                Spacer(Modifier.height(20.dp))

                if (!codeWasSent) {
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { input -> phoneNumber = input.filter { it.isDigit() || it == '+' } },
                        label = { Text("Phone number") },
                        placeholder = { Text("+15551234567") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )

                    Spacer(Modifier.height(16.dp))

                    Button(
                        onClick = { startPhoneVerification(forceResend = false) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isLoading
                    ) {
                        Text("Send OTP")
                    }
                } else {
                    Text("Enter the 6-digit code sent to $phoneNumber", fontSize = 13.sp)

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = otpCode,
                        onValueChange = { input -> if (input.length <= 6) otpCode = input.filter { it.isDigit() } },
                        label = { Text("OTP code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isLoading
                    )

                    Spacer(Modifier.height(16.dp))

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

                            // Updated to use GitLive's PhoneAuthProvider and Credential
                            val gitLiveCredential = dev.gitlive.firebase.auth.PhoneAuthProvider().credential(id, otpCode)
                            signInWithGitLiveCredential(gitLiveCredential, coroutineScope, context,
                                onSignedIn) { isLoading = it }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = !isLoading
                    ) {
                        Text("Verify")
                    }

                    Spacer(Modifier.height(8.dp))

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
                        Text("Change phone number")
                    }
                }

                if (isLoading) {
                    Spacer(Modifier.height(20.dp))
                    CircularProgressIndicator()
                }
            }
        }
    }

    // New: Executes sign-in using pure GitLive KMP Firebase Auth
    private fun signInWithGitLiveCredential(
        credential: AuthCredential,
        scope: CoroutineScope,
        context: android.content.Context,
        onSignedIn: () -> Unit,
        setLoading: (Boolean) -> Unit
    ) {
        scope.launch {
            setLoading(true)
            try {
                Firebase.auth.signInWithCredential(credential)

//                Firebase.auth.currentUser?.let { firebaseUser ->
//                    val profileResult = userRepo.ensureUserProfileExists(
//                        uid = firebaseUser.uid,
//                        displayName = firebaseUser.displayName,
//                        email = firebaseUser.email,
//                        phoneNumber = firebaseUser.phoneNumber,
//                        photoUrl = firebaseUser.photoURL
//                    )
//                    profileResult.e?.let { profileError ->
//                        Toast.makeText(
//                            context,
//                            "Signed in, but couldn't save your profile: ${profileError.message}",
//                            Toast.LENGTH_LONG
//                        ).show()
//                    }
//                }

                onSignedIn()
            } catch (e: Exception) {
                Toast.makeText(context, e.message ?: "Invalid code, try again", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    // Existing: Kept exclusively for native auto-verification (onVerificationCompleted)
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
                val result = FirebaseAuth.getInstance().signInWithCredential(credential).await()

//                result.user?.let { firebaseUser ->
//                    val profileResult = userRepo.ensureUserProfileExists(
//                        uid = firebaseUser.uid,
//                        displayName = firebaseUser.displayName,
//                        email = firebaseUser.email,
//                        phoneNumber = firebaseUser.phoneNumber,
//                        photoUrl = firebaseUser.photoUrl?.toString()
//                    )
//                    profileResult.e?.let { profileError ->
//                        Toast.makeText(
//                            context,
//                            "Signed in, but couldn't save your profile: ${profileError.message}",
//                            Toast.LENGTH_LONG
//                        ).show()
//                    }
//                }

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