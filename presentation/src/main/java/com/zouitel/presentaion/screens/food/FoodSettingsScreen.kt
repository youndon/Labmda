package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.model.FoodMode
import com.zouitel.presentaion.screens.account.AccountScreenModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FoodSettingsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val accountScreenModel = koinScreenModel<AccountScreenModel>()
        val currentUser by accountScreenModel.currentUser.collectAsState()
        val profile by accountScreenModel.profile.collectAsState()
        val scope = rememberCoroutineScope()

        // Fetch user's current cloud mode on launch
        LaunchedEffect(currentUser?.uid) {
            currentUser?.uid?.let { uid ->
                try {
                    accountScreenModel.getUserProfile(uid).collect {
                        it?.foodMode ?: FoodMode.CLIENT
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        fun onModeSelected(selectedMode: FoodMode) {
            currentUser?.uid?.let { uid ->
                scope.launch(Dispatchers.IO) {
                    accountScreenModel.updateFoodUserMode(uid, selectedMode)
                }
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("App Settings & Mode") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Select Operating Mode",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                // Client Mode Card
                ModeSelectionCard(
                    title = "Client Mode",
                    description = "Browse restaurants, place meal orders, and track active deliveries.",
                    icon = Icons.Default.Person,
                    isSelected = profile?.foodMode == FoodMode.CLIENT,
                    onClick = { onModeSelected(FoodMode.CLIENT) }
                )

                // Manager Mode Card
                ModeSelectionCard(
                    title = "Restaurant Manager Mode",
                    description = "Receive orders live from clients, manage kitchen progress, and update status.",
                    icon = Icons.Default.Badge,
                    isSelected = profile?.foodMode == FoodMode.MANAGER,
                    onClick = { onModeSelected(FoodMode.MANAGER) }
                )

                if (profile?.foodMode == FoodMode.MANAGER) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { navigator?.push(DashboardScreen()) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Store, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Manager Dashboard")
                    }
                }
            }
        }
    }

    @Composable
    private fun ModeSelectionCard(
        title: String,
        description: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        isSelected: Boolean,
        onClick: () -> Unit
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            colors = CardDefaults.cardColors(
                containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 4.dp else 1.dp)
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isSelected,
                    onClick = onClick
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = description, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}