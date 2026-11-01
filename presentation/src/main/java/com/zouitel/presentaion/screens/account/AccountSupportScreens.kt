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
        Scaffold(topBar = { SupportTopBar("Notifications", { navigator.pop() }) }) { padding ->
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
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(22.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Your account, your control",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "A few simple steps can help keep your Labmda account and personal information safer.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)
                            ) {
                                Text(
                                    "Security checklist",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                item {
                    Text("Protect your account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PreferenceCard {
                        FaqItem("Use a strong password", "Choose a unique password that you do not use for other apps or websites.")
                        HorizontalDivider()
                        FaqItem("Keep sign-in codes private", "Never share one-time codes, passwords or payment credentials with anyone.")
                        HorizontalDivider()
                        FaqItem("Secure your email account", "Your email may be used to recover access. Keep it secure and review unexpected sign-in messages.")
                    }
                }
                item {
                    Text("Device permissions", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PreferenceCard {
                        FaqItem(
                            "Review access when needed",
                            "Check location, camera, photos and notification access in Android settings. Allow only the permissions needed for features you use."
                        )
                        Button(
                            onClick = {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                context.startActivity(intent)
                            },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)
                        ) {
                            Text("Open Android app settings")
                        }
                    }
                }
                item {
                    Text("Privacy information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    PreferenceCard {
                        FaqItem(
                            "Using a shared device",
                            "Sign out when you finish using Labmda on a device that other people can access."
                        )
                        HorizontalDivider()
                        FaqItem(
                            "Need account or privacy help?",
                            "Account deletion and formal privacy-policy requests are not available from this screen yet. Contact Labmda support for assistance."
                        )
                    }
                }
                item {
                    Text(
                        "Opening Android settings lets you review the permissions granted to this app. It does not change your account credentials or submit a privacy request.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
            runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
            }.getOrDefault("Unknown")
        }
        val services = listOf("Food", "Shops", "Rides", "Delivery")
        Scaffold(topBar = { SupportTopBar("About Labmda", navigator::pop) }) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Card(
                        Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(26.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = MaterialTheme.colorScheme.primary
                            ) {
                                Text(
                                    "L",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                                    style = MaterialTheme.typography.displaySmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(
                                "Labmda",
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Food, shops, rides and delivery",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                            ) {
                                Text(
                                    "Version $version",
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Everyday services, together", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "Labmda brings everyday local services together in one app.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                item {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Explore Labmda", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            services.take(2).forEach { service ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        service,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            services.drop(2).forEach { service ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.secondaryContainer
                                ) {
                                    Text(
                                        service,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    HorizontalDivider()
                    Text(
                        "App information",
                        modifier = Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Application", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Labmda", fontWeight = FontWeight.Medium)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Installed version", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(version, fontWeight = FontWeight.Medium)
                    }
                }
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
