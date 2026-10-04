package com.zouitel.presentaion.screens.account

import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.zouitel.presentaion.model.DriveMode
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.launch

class AccountScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        val screenModel = koinScreenModel<AccountScreenModel>()
        val user by screenModel.currentUser.collectAsState()
        val profile by screenModel.profile.collectAsState()

        // --- Fetch (or initialize) the user's Firestore profile ---
//        var userProfile by remember { mutableStateOf(profile) }
        var isInitializingProfile by remember { mutableStateOf(true) }
        var isUpdatingMode by remember { mutableStateOf(false) }

        LaunchedEffect(user?.uid) {
            val firebaseUser = user
            val uid = firebaseUser?.uid
            if (uid == null) {
                isInitializingProfile = false
                return@LaunchedEffect
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Account") },
                    actions = {
                        IconButton(onClick = { navigator.push(SettingsScreen()) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp)
            ) {
                Spacer(Modifier.height(12.dp))

                // --- Profile header ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val photoUrl = user?.photoURL
                    if (photoUrl != null) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = "Profile photo",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = "Profile photo",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column {
                        Text(
                            text = user?.displayName?.takeIf { it.isNotBlank() } ?: profile?.name ?: "Guest",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = user?.email ?: user?.phoneNumber ?: "Not signed in",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))

                // --- Mode Switching Card ---
                Switch(
                    checked = profile?.driveMode == DriveMode.DRIVER,
                    onCheckedChange = {
                        coroutineScope.launch {
                            screenModel.updateUserMode(user?.uid ?: "", if (it) DriveMode.DRIVER else DriveMode.PASSENGER)
                        }
                    }

                )

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()

                // --- Account actions ---
                AccountRow(Icons.Filled.History, "Ride history") { /* TODO */ }
                AccountRow(Icons.Filled.CreditCard, "Payment methods") { /* TODO */ }
                AccountRow(Icons.Filled.NotificationsNone, "Notifications") { /* TODO */ }
                AccountRow(Icons.Filled.HelpOutline, "Help & support") { /* TODO */ }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                AccountRow(Icons.Filled.Settings, "Settings") { navigator.push(SettingsScreen()) }
                AccountRow(
                    icon = Icons.Filled.Logout,
                    label = "Sign out",
                    tint = MaterialTheme.colorScheme.error
                ) {
                    coroutineScope.launch {
                        Firebase.auth.signOut()
                    }
                    Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    @Composable
    private fun ModeSwitchCard(
        currentMode: DriveMode,
        isLoading: Boolean,
        isApprovedDriver: Boolean,
        onModeChanged: (DriveMode) -> Unit
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Account Mode",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    ModeOptionSegment(
                        label = "Passenger",
                        icon = Icons.Filled.Person,
                        isSelected = currentMode == DriveMode.PASSENGER,
                        isLoading = isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = { if (currentMode != DriveMode.PASSENGER) onModeChanged(DriveMode.PASSENGER) }
                    )

                    Spacer(modifier = Modifier.width(4.dp))

                    ModeOptionSegment(
                        label = "Driver",
                        icon = Icons.Filled.DirectionsCar,
                        isSelected = currentMode == DriveMode.DRIVER,
                        isLoading = isLoading,
                        modifier = Modifier.weight(1f),
                        onClick = { if (currentMode != DriveMode.DRIVER) onModeChanged(DriveMode.DRIVER) }
                    )
                }

                if (!isApprovedDriver) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Driver approval is required to unlock Driver mode.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }

    @Composable
    private fun ModeOptionSegment(
        label: String,
        icon: ImageVector,
        isSelected: Boolean,
        isLoading: Boolean,
        modifier: Modifier = Modifier,
        onClick: () -> Unit
    ) {
        val backgroundColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
            label = "segment_background"
        )
        val contentColor by animateColorAsState(
            targetValue = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "segment_content"
        )

        Surface(
            onClick = onClick,
            enabled = !isLoading,
            modifier = modifier.height(44.dp),
            shape = RoundedCornerShape(10.dp),
            color = backgroundColor
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isLoading && isSelected) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = contentColor,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(text = label, modifier = Modifier.weight(1f), color = tint)
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}