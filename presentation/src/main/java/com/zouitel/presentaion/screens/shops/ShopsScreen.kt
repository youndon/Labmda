package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import com.zouitel.presentaion.common.AppCard
import com.zouitel.presentaion.common.AppCardContent
import com.zouitel.presentaion.common.AppSectionTitle
import com.zouitel.presentaion.common.AppSpacing
import com.zouitel.presentaion.screens.account.AccountScreenModel
import com.zouitel.presentaion.screens.food.Restaurant

class ShopsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopsScreenModel() }
        val accountModel = koinScreenModel<AccountScreenModel>()
        val approvedShops by screenModel.approvedShops.collectAsState()
        val userProfile by accountModel.profile.collectAsState()
        val managerShop by screenModel.managerShop.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        var searchQuery by remember { mutableStateOf("") }
        var availabilityFilter by remember { mutableStateOf("All") }
        val filteredShops = remember(approvedShops, searchQuery, availabilityFilter) {
            approvedShops.filter { shop ->
                val query = searchQuery.trim()
                val matchesSearch = query.isBlank() || listOf(
                    shop.name, shop.description, shop.category, shop.address
                ).any { it.contains(query, ignoreCase = true) }
                val matchesAvailability = when (availabilityFilter) {
                    "Online" -> shop.isOnline
                    "Offline" -> !shop.isOnline
                    else -> true
                }
                matchesSearch && matchesAvailability
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Available Shops & Stores", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { navigator?.push(ShopActivitiesScreen()) }) {
                            Icon(Icons.Default.AutoAwesomeMosaic, contentDescription = "My Client Store Activities")
                        }
                    }
                )
            }
        ) { paddingValues ->
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (approvedShops.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "No verified shops available right now.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.md)) {
                            AppSectionTitle(
                                title = "Discover local shops",
                                subtitle = "Browse verified stores and find everyday essentials."
                            )
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                shape = MaterialTheme.shapes.large,
                                placeholder = { Text("Search shops, categories, or addresses") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                            )
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                            ) {
                                listOf("All", "Online", "Offline").forEach { filter ->
                                    FilterChip(
                                        selected = availabilityFilter == filter,
                                        onClick = { availabilityFilter = filter },
                                        label = { Text(filter) }
                                    )
                                }
                            }
                            Text(
                                text = "${filteredShops.size} ${if (filteredShops.size == 1) "shop" else "shops"} available",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (filteredShops.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Outlined.Storefront,
                                        contentDescription = null,
                                        modifier = Modifier.size(40.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text("No matching shops", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(
                                        "Try another search or availability filter.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                        }
                    } else {
                        items(filteredShops, key = { it.id }) { shop ->
                            ShopCardItem(shop = shop, onClick = { navigator?.push(StoreDetailsScreen(shop)) })
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ShopCardItem(shop: Shop, onClick: () -> Unit) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            shape = MaterialTheme.shapes.large
        ) {
            Column {
                AsyncImage(
                    model = shop.imageUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    contentScale = ContentScale.Crop
                )

                AppCardContent {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = shop.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))

                        Surface(
                            color = if (shop.isOnline) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (shop.isOnline) "Online" else "Offline",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = if (shop.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Delivery fee calculated at checkout",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "ETA shown after address selection",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}