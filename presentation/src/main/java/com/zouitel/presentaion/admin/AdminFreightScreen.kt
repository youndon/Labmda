package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status

class AdminFreightScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { AdminFreightModel() }
        val applications by screenModel.applications.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Freight Applications") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            when {
                isLoading -> Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                applications.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) { Text("No pending freight applications.") }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(applications, key = { it.uid }) { app ->
                        FreightApplicationCard(
                            app = app,
                            onApprove = { screenModel.updateFreightStatus(app.uid, Status.APPROVED) },
                            onReject = { screenModel.updateFreightStatus(app.uid, Status.REJECTED) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun FreightApplicationCard(
        app: FreightApplication,
        onApprove: () -> Unit,
        onReject: () -> Unit
    ) {
        val uriHandler = LocalUriHandler.current

        Card(modifier = Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(app.fullName.ifBlank { "Unnamed applicant" }, style = MaterialTheme.typography.titleMedium)
                Text("Phone: ${app.phone.ifBlank { "-" }}", style = MaterialTheme.typography.bodyMedium)
                Text("Vehicle: ${app.vehicleType.ifBlank { "-" }}", style = MaterialTheme.typography.bodyMedium)

                if (app.documentLink.isNotBlank()) {
                    TextButton(
                        onClick = { runCatching { uriHandler.openUri(app.documentLink) } },
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text("Open ID / document link")
                    }
                } else {
                    Text("No document provided", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Approve")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reject")
                    }
                }
            }
        }
    }
}