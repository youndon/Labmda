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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import coil.compose.AsyncImage
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile

class AdminRestaurantScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { AdminRestaurantModel() }
        val applications by screenModel.applications.collectAsState()
        val error by screenModel.error.collectAsState()
        val reviewingUid by screenModel.reviewingUid.collectAsState()
        Scaffold(topBar = {
            TopAppBar(
                title = { Text("Restaurant Applications") },
                navigationIcon = {
                    IconButton(onClick = { navigator?.pop() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                if (applications.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No pending restaurant applications.")
                    }
                } else LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(applications, key = { it.uid }) { profile ->
                        Card(Modifier.fillMaxWidth(), elevation = CardDefaults.cardElevation(3.dp)) {
                            Column(Modifier.padding(16.dp)) {
                                if (profile.restaurantImageUrl.isNotBlank()) {
                                    AsyncImage(
                                        model = profile.restaurantImageUrl,
                                        contentDescription = "Restaurant photo",
                                        modifier = Modifier.fillMaxWidth().height(168.dp),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(Modifier.height(12.dp))
                                }
                                Text(profile.restaurantName.ifBlank { "Restaurant name not provided" }, style = MaterialTheme.typography.titleLarge)
                                Text(profile.restaurantAddress.ifBlank { "Address not provided" }, style = MaterialTheme.typography.bodyMedium)
                                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                                Text("Manager: ${profile.fullName.ifBlank { "Not provided" }}")
                                Text("Phone: ${profile.phoneNumber?.ifBlank { "Not provided" } ?: "Not provided"}")
                                Spacer(Modifier.height(16.dp))
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = { screenModel.updateRestaurantStatus(profile.uid, Status.APPROVED) },
                                        enabled = reviewingUid != profile.uid,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Check, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Approve")
                                    }
                                    OutlinedButton(
                                        onClick = { screenModel.updateRestaurantStatus(profile.uid, Status.REJECTED) },
                                        enabled = reviewingUid != profile.uid,
                                        modifier = Modifier.weight(1f),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                                    ) {
                                        Icon(Icons.Default.Close, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Reject")
                                    }
                                }
                                if (reviewingUid == profile.uid) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}