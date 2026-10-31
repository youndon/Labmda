package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import com.zouitel.presentaion.common.MarketplaceAvailabilityBadge
import com.zouitel.presentaion.common.MarketplaceAvailabilityFilters
import com.zouitel.presentaion.common.MarketplaceEmptyState
import com.zouitel.presentaion.common.MarketplaceInfo
import com.zouitel.presentaion.common.MarketplaceSearchField

class ShopsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopsScreenModel() }
        val approvedShops by screenModel.approvedShops.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        var query by remember { mutableStateOf("") }
        var showOpenOnly by remember { mutableStateOf(false) }

        val openCount = approvedShops.count { it.isOnline }
        val visibleShops = remember(approvedShops, query, showOpenOnly) {
            val search = query.trim()
            approvedShops.filter { shop ->
                (!showOpenOnly || shop.isOnline) &&
                    (search.isBlank() ||
                        shop.name.contains(search, ignoreCase = true) ||
                        shop.category.contains(search, ignoreCase = true) ||
                        shop.description.contains(search, ignoreCase = true) ||
                        shop.address.contains(search, ignoreCase = true))
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Shops", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { navigator?.push(ShopActivitiesScreen()) }) {
                            Icon(
                                Icons.Default.AutoAwesomeMosaic,
                                contentDescription = "My shop activities"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(start = 16.dp, top = 18.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Everyday essentials, made easy",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                "Browse local stores and find what you need.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        MarketplaceSearchField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search shops, stores or categories"
                        )

                        MarketplaceAvailabilityFilters(
                            showOpenOnly = showOpenOnly,
                            onShowOpenOnlyChange = { showOpenOnly = it },
                            totalCount = approvedShops.size,
                            openCount = openCount
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Stores", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${visibleShops.size} found",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (visibleShops.isEmpty()) {
                    item {
                        MarketplaceEmptyState(
                            icon = Icons.Outlined.Storefront,
                            title = if (approvedShops.isEmpty()) "No verified shops yet" else "No matching shops",
                            message = if (approvedShops.isEmpty()) {
                                "Approved stores will appear here when they are ready to take orders."
                            } else {
                                "Try another search or switch back to all stores."
                            }
                        )
                    }
                } else {
                    items(visibleShops, key = { it.id }) { shop ->
                        ShopCardItem(
                            shop = shop,
                            onClick = { navigator?.push(StoreDetailsScreen(shop)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ShopCardItem(
    shop: Shop,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(184.dp)
            ) {
                if (shop.imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = shop.imageUrl,
                        contentDescription = "${shop.name} cover",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.Storefront,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                MarketplaceAvailabilityBadge(
                    isOpen = shop.isOnline,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = shop.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (shop.category.isNotBlank()) {
                    Text(
                        text = shop.category,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (shop.description.isNotBlank()) {
                    Text(
                        text = shop.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (shop.address.isNotBlank()) {
                    MarketplaceInfo(
                        icon = Icons.Default.LocationOn,
                        label = shop.address
                    )
                }

                MarketplaceInfo(
                    icon = Icons.Default.DeliveryDining,
                    label = "Distance-based delivery"
                )
            }
        }
    }
}
