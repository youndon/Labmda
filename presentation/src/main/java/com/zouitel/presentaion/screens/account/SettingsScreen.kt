package com.zouitel.presentaion.screens.account

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow

class SettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val model = koinScreenModel<AccountPreferencesScreenModel>()
        val preferences by model.preferences.collectAsState()
        var showLanguageDialog by remember { mutableStateOf(false) }

        LaunchedEffect(preferences.language) {
            val locales = if (preferences.language == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(preferences.language)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Settings", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Text("Preferences", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                item {
                    SettingsCard {
                        SettingsSwitchRow(
                            icon = Icons.Filled.NotificationsNone,
                            label = "Notification Preference",
                            checked = preferences.notificationsEnabled,
                            onCheckedChange = model::setNotificationsEnabled
                        )
                        HorizontalDivider()
                        SettingsSwitchRow(
                            icon = Icons.Filled.DarkMode,
                            label = "Dark Mode Theme",
                            checked = preferences.darkModeEnabled,
                            onCheckedChange = model::setDarkModeEnabled
                        )
                    }
                }
                item {
                    Text("General & Privacy", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
                item {
                    SettingsCard {
                        SettingsNavigationRow(Icons.Filled.Language, "App Language", preferences.languageLabel()) {
                            showLanguageDialog = true
                        }
                        HorizontalDivider()
                        SettingsNavigationRow(Icons.Filled.Lock, "Privacy & Security", "Manage app permissions and account safety") {
                            navigator.push(PrivacySecurityScreen())
                        }
                        HorizontalDivider()
                        SettingsNavigationRow(Icons.Filled.Info, "About Labmda", "App version and information") {
                            navigator.push(AboutAppScreen())
                        }
                    }
                }
                item {
                    Text(
                        "Preferences are saved on this device. Some app text may remain in English until translated resources are added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (showLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text("App language") },
                text = {
                    Column {
                        listOf("system" to "System default", "en" to "English").forEach { (code, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable { model.setLanguage(code); showLanguageDialog = false }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = preferences.language == code, onClick = {
                                    model.setLanguage(code)
                                    showLanguageDialog = false
                                })
                                Text(label, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { showLanguageDialog = false }) { Text("Close") } }
            )
        }
    }
}

private fun AccountPreferences.languageLabel(): String = when (language) {
    "en" -> "English"
    else -> "System default"
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) { Column(content = content) }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Text(label, modifier = Modifier.weight(1f).padding(horizontal = 14.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsNavigationRow(icon: ImageVector, label: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null)
    }
}
