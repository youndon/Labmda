package com.zouitel.presentaion.screens.account

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.zouitel.presentaion.R
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
        val systemDark = isSystemInDarkTheme()
        val darkModeActive = preferences.darkModeEnabled ?: systemDark

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
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                item {
                    SettingsOverviewCard(
                        notificationsEnabled = preferences.notificationsEnabled,
                        darkModeActive = darkModeActive,
                        languageLabel = preferences.languageLabel()
                    )
                }
                item {
                    SettingsSectionHeader("Preferences", "Control the way Labmda behaves on this device")
                    SettingsCard {
                        SettingsSwitchRow(
                            icon = Icons.Filled.NotificationsNone,
                            label = "Notifications",
                            subtitle = "Choose whether app notifications are enabled",
                            checked = preferences.notificationsEnabled,
                            onCheckedChange = model::setNotificationsEnabled
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                        SettingsSwitchRow(
                            icon = Icons.Filled.DarkMode,
                            label = "Dark mode",
                            subtitle = if (preferences.darkModeEnabled == null) "Following your device setting" else if (darkModeActive) "Dark appearance is enabled" else "Light appearance is enabled",
                            checked = darkModeActive,
                            onCheckedChange = model::setDarkModeEnabled
                        )
                    }
                }
                item {
                    SettingsSectionHeader("General & privacy", "Language, account safety and app details")
                    SettingsCard {
                        SettingsNavigationRow(
                            icon = Icons.Filled.Language,
                            label = stringResource(R.string.app_language),
                            subtitle = preferences.languageLabel(),
                            onClick = { showLanguageDialog = true }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                        SettingsNavigationRow(
                            icon = Icons.Filled.Lock,
                            label = "Privacy & Security",
                            subtitle = "Manage app permissions and account safety",
                            onClick = { navigator.push(PrivacySecurityScreen()) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
                        SettingsNavigationRow(
                            icon = Icons.Filled.Info,
                            label = "About Labmda",
                            subtitle = "App version and information",
                            onClick = { navigator.push(AboutAppScreen()) }
                        )
                    }
                }
                item {
                    Text(
                        "Your preferences are stored on this device. Some app text may remain in English until translated resources are added.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
                    )
                }
            }
        }

        if (showLanguageDialog) {
            AlertDialog(
                onDismissRequest = { showLanguageDialog = false },
                title = { Text(stringResource(R.string.app_language_dialog_title)) },
                text = {
                    Column {
                        listOf(
                            "system" to stringResource(R.string.language_system_default),
                            "en" to "English",
                            "fil" to "Filipino",
                            "ceb" to "Cebuano"
                        ).forEach { (code, label) ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    model.setLanguage(code)
                                    showLanguageDialog = false
                                }.padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = preferences.language == code,
                                    onClick = {
                                        model.setLanguage(code)
                                        showLanguageDialog = false
                                    }
                                )
                                Text(label, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showLanguageDialog = false }) {
                        Text(stringResource(R.string.close))
                    }
                }
            )
        }
    }
}

private fun AccountPreferences.languageLabel(): String = when (language) {
    "en" -> "English"
    "fil" -> "Filipino"
    "ceb" -> "Cebuano"
    else -> "System default"
}

@Composable
private fun SettingsOverviewCard(
    notificationsEnabled: Boolean,
    darkModeActive: Boolean,
    languageLabel: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(12.dp).size(25.dp)
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("Make Labmda yours", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Text("Your app, your preferences", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SettingsSummaryPill("Notifications", if (notificationsEnabled) "On" else "Off", Modifier.weight(1f))
                SettingsSummaryPill("Appearance", if (darkModeActive) "Dark" else "Light", Modifier.weight(1f))
                SettingsSummaryPill("Language", languageLabel, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SettingsSummaryPill(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Column(content = content)
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(10.dp).size(21.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsNavigationRow(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(10.dp).size(21.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
