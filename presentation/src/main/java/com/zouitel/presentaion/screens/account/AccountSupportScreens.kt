package com.zouitel.presentaion.screens.account

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

class NotificationPreferencesScreen : Screen {
    @Composable
    override fun Content() {
        val model = koinScreenModel<AccountPreferencesScreenModel>()
        val preferences by model.preferences.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
        Scaffold(topBar = { SupportTopBar("Notifications", navigator::pop) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(20.dp)) {
                PreferenceCard {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NotificationsNone, contentDescription = null)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("Notifications", fontWeight = FontWeight.SemiBold)
                            Text("Save your in-app notification preference.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(preferences.notificationsEnabled, model::setNotificationsEnabled)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Android notification permission is managed separately in device settings. This preference is saved locally; it does not revoke Android permission or change server-side push delivery.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

class HelpSupportScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        Scaffold(topBar = { SupportTopBar("Help & Support", navigator::pop) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item { Text("Frequently asked questions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                item {
                    PreferenceCard {
                        FaqItem("Where can I find an order or ride?", "Open Account Center → Orders & Ride History.")
                        HorizontalDivider()
                        FaqItem("Where can I manage payments?", "Open Account Center → Payments & Activity. Provider earnings and payout accounts appear when available to your account.")
                        HorizontalDivider()
                        FaqItem("How do I change app preferences?", "Open Application Settings to update saved notification, appearance and language preferences.")
                        HorizontalDivider()
                        FaqItem("I need more help.", "Share a support request using the button below.")
                    }
                }
                item {
                    Button(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Labmda support request")
                                putExtra(Intent.EXTRA_TEXT, "Please describe the issue you are experiencing:\n\nApp: Labmda\nDevice: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
                            }
                            context.startActivity(Intent.createChooser(intent, "Send support request"))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Share a support request") }
                }
                item { Text("Choose an email or messaging app from your device's share sheet and send the request to the support contact you use.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

class PrivacySecurityScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        Scaffold(topBar = { SupportTopBar("Privacy & Security", navigator::pop) }) { padding ->
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    PreferenceCard {
                        FaqItem("Protect your account", "Use a strong, unique password and never share sign-in codes or payment credentials.")
                        HorizontalDivider()
                        FaqItem("App permissions", "Review location, camera, photos and notification permissions in Android settings. Grant permissions only when you use the related feature.")
                        HorizontalDivider()
                        FaqItem("Sign-in security", "Sign out when using a shared device. If your account may be compromised, secure the email account associated with your Labmda sign-in.")
                    }
                }
                item {
                    Button(onClick = {
                        val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        context.startActivity(intent)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Open Android app permissions") }
                }
                item { Text("Account deletion and formal privacy-policy requests are not available from this screen yet. Contact support for assistance.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

class AboutAppScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown" }.getOrDefault("Unknown")
        }
        Scaffold(topBar = { SupportTopBar("About Labmda", navigator::pop) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Labmda", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text("Food, shops, rides and delivery")
                Spacer(Modifier.height(18.dp))
                Text("Version $version", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(24.dp))
                Text("Labmda brings everyday local services together in one app.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SupportTopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(title = { Text(title, fontWeight = FontWeight.Bold) }, navigationIcon = {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
    })
}

@Composable
private fun PreferenceCard(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(content = content)
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(question, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(answer, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
