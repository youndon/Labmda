package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
        var query by remember { mutableStateOf("") }
        val filtered = pendingShops.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.category.contains(query, ignoreCase = true) ||
                it.phone.contains(query, ignoreCase = true) ||
                it.address.contains(query, ignoreCase = true)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Shop applications", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            when {
                isLoading -> androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    item {
                        AdminApplicationHeader(
                            count = pendingShops.size,
                            description = "Review shop details and contact information before approving a store.",
                            query = query,
                            onQueryChange = { query = it },
                            searchPlaceholder = "Search shop, category, phone or address"
                        )
                    }
                    if (filtered.isEmpty()) {
                        item {
                            AdminApplicationEmptyState(
                                title = if (pendingShops.isEmpty()) "No pending shop applications" else "No matching shops",
                                description = if (pendingShops.isEmpty()) "New shop applications will appear here." else "Try another shop name, category or address."
                            )
                        }
                    } else {
                        items(filtered, key = { it.id }) { shop ->
                            ShopApplicationCard(
                                shop = shop,
                                onApprove = { screenModel.updateShopStatus(shop.id, shop.ownerUid, Status.APPROVED) },
                                onReject = { screenModel.updateShopStatus(shop.id, shop.ownerUid, Status.REJECTED) }
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ShopApplicationCard(shop: com.zouitel.presentaion.screens.shops.Shop, onApprove: () -> Unit, onReject: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(shop.name.ifBlank { "Shop applicant" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(shop.category.ifBlank { "Uncategorized shop" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AdminPendingLabel()
                }
                Text(shop.phone.ifBlank { "Phone not provided" }, style = MaterialTheme.typography.bodyMedium)
                Text(shop.address.ifBlank { "Address not provided" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (shop.description.isNotBlank()) {
                    Text(shop.description, style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Approve")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Reject")
                    }
                }
            }
        }
    }
}