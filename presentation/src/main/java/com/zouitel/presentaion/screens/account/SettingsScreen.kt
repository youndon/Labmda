package com.zouitel.presentaion.screens.account

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.zouitel.presentaion.screens.MainScreen
import com.zouitel.presentaion.screens.courier.CourierWorkplaceScreen
import com.zouitel.presentaion.screens.freight.FreightWorkplaceScreen
import com.zouitel.presentaion.screens.ride.DriverWorkplaceScreen

class SettingsScreen() : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        // Grab the root navigator to replace the entire MainScreen stack
        val rootNavigator = navigator.parent ?: navigator

        var isDriverMode by remember { mutableStateOf(true) }
        var notificationsEnabled by remember { mutableStateOf(true) }
        var darkModeEnabled by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Settings") },
                    navigationIcon = {
                        IconButton(onClick = { navigator.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Spacer(Modifier.height(8.dp))

                // --- Driver / Passenger Mode Switch ---
                SettingsSwitchRow(
                    icon = Icons.Filled.DirectionsCar,
                    label = "Driver Mode",
                    checked = isDriverMode,
                    onCheckedChange = { newMode ->
                        isDriverMode = newMode
                        // TODO: Save this preference to your DataStore/SharedPreferences here

                        // Restart the main screen with the newly selected mode
                        rootNavigator.replaceAll(MainScreen())
                    }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchRow(
                    icon = Icons.Filled.NotificationsNone,
                    label = "Push notifications",
                    checked = notificationsEnabled,
                    onCheckedChange = { notificationsEnabled = it }
                )
                SettingsSwitchRow(
                    icon = Icons.Filled.DarkMode,
                    label = "Dark mode",
                    checked = darkModeEnabled,
                    onCheckedChange = { darkModeEnabled = it }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsRow(Icons.Filled.Lock, "Privacy & security") { /* TODO */ }
                SettingsRow(Icons.Filled.Language, "Language") { /* TODO */ }
                SettingsRow(Icons.Filled.Info, "About") { /* TODO */ }
            }
        }
    }
}

@Composable
private fun SettingsRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}