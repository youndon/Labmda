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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status

class AdminShopsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { AdminShopsModel() }
        val pendingShops by screenModel.pendingShops.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Pending Shop Applications") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (pendingShops.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Text("No pending store applications.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(pendingShops, key = { it.id }) { shop ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(text = shop.name, style = MaterialTheme.typography.titleMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = "Category: ${shop.category}", style = MaterialTheme.typography.bodySmall)
                                Text(text = "Phone: ${shop.phone}", style = MaterialTheme.typography.bodySmall)
                                Text(text = "Address: ${shop.address}", style = MaterialTheme.typography.bodySmall)
                                if (shop.description.isNotBlank()) {
                                    Text(text = "Description: ${shop.description}", style = MaterialTheme.typography.bodySmall)
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { screenModel.updateShopStatus(shop.id, Status.APPROVED) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Approve")
                                    }

                                    OutlinedButton(
                                        onClick = { screenModel.updateShopStatus(shop.id, Status.REJECTED) },
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
            }
        }
    }
}