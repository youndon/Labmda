package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DeliveryDining
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Restaurant
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
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import coil3.compose.AsyncImage
import com.zouitel.presentaion.common.MarketplaceAvailabilityBadge
import com.zouitel.presentaion.common.MarketplaceAvailabilityFilters
import com.zouitel.presentaion.common.MarketplaceEmptyState
import com.zouitel.presentaion.common.MarketplaceErrorState
import com.zouitel.presentaion.common.MarketplaceInlineError
import com.zouitel.presentaion.common.MarketplaceInfo
import com.zouitel.presentaion.common.MarketplaceSearchField
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.screens.account.AccountScreenModel
import com.zouitel.presentaion.screens.delivery.DeliveryDashboardScreen

class RestaurantsScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = koinScreenModel<RestaurantsScreenModel>()
        val accountModel = koinScreenModel<AccountScreenModel>()
        val restaurants by screenModel.restaurants.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val error by screenModel.error.collectAsState()
        val profile by accountModel.profile.collectAsState()

        var query by remember { mutableStateOf("") }
        var showOpenOnly by remember { mutableStateOf(false) }

        val openCount = restaurants.count { it.isOpen }
        val visibleRestaurants = remember(restaurants, query, showOpenOnly) {
            val search = query.trim()
            restaurants.filter { restaurant ->
                (!showOpenOnly || restaurant.isOpen) &&
                    (search.isBlank() ||
                        restaurant.name.contains(search, ignoreCase = true) ||
                        restaurant.address.contains(search, ignoreCase = true) ||
                        restaurant.menu.any { meal ->
                            meal.name.contains(search, ignoreCase = true) ||
                                meal.description.contains(search, ignoreCase = true)
                        })
            }
        }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Food", fontWeight = FontWeight.Bold) },
                    actions = {
                        when (profile?.foodMode) {
                            UserMode.MANAGER -> IconButton(onClick = { navigator?.push(FoodOrdersScreen()) }) {
                                Icon(Icons.Default.Dashboard, contentDescription = "Restaurant dashboard")
                            }
                            UserMode.COURIER -> IconButton(onClick = { navigator?.push(DeliveryDashboardScreen()) }) {
                                Icon(Icons.Default.DirectionsBike, contentDescription = "Delivery dashboard")
                            }
                            else -> IconButton(onClick = { navigator?.push(FoodActivitiesScreen()) }) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = "My food orders")
                            }
                        }
                        IconButton(onClick = { navigator?.push(FoodSettingsScreen()) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Food settings")
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
                                "Find your next favourite",
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                "Explore local kitchens and order your meal.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        MarketplaceSearchField(
                            query = query,
                            onQueryChange = { query = it },
                            placeholder = "Search restaurants or dishes"
                        )

                        MarketplaceAvailabilityFilters(
                            showOpenOnly = showOpenOnly,
                            onShowOpenOnlyChange = { showOpenOnly = it },
                            totalCount = restaurants.size,
                            openCount = openCount
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Restaurants", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${visibleRestaurants.size} found",
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
                } else if (error != null && restaurants.isEmpty()) {
                    item {
                        MarketplaceErrorState(
                            title = "Restaurants unavailable",
                            message = error ?: "Restaurants could not be loaded.",
                            onRetry = screenModel::refresh
                        )
                    }
                } else if (visibleRestaurants.isEmpty()) {
                    item {
                        MarketplaceEmptyState(
                            icon = Icons.Outlined.Restaurant,
                            title = if (restaurants.isEmpty()) "No restaurants yet" else "No matching restaurants",
                            message = if (restaurants.isEmpty()) {
                                "Restaurants will appear here when they are available on Labmda."
                            } else {
                                "Try another name or clear the open-now filter to see more places."
                            }
                        )
                    }
                } else {
                    if (error != null) {
                        item { MarketplaceInlineError(error ?: "", screenModel::refresh) }
                    }
                    items(visibleRestaurants, key = { it.id }) { restaurant ->
                        RestaurantCardItem(
                            restaurant = restaurant,
                            onClick = { navigator?.push(RestaurantScreen(restaurant)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RestaurantCardItem(
    restaurant: Restaurant,
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
                if (restaurant.imageUrl.isNotBlank()) {
                    AsyncImage(
                        model = restaurant.imageUrl,
                        contentDescription = "${restaurant.name} cover",
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
                            Icons.Outlined.Restaurant,
                            contentDescription = null,
                            modifier = Modifier.size(42.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                MarketplaceAvailabilityBadge(
                    isOpen = restaurant.isOpen,
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = restaurant.name,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (restaurant.address.isNotBlank()) {
                    MarketplaceInfo(
                        icon = Icons.Default.LocationOn,
                        label = restaurant.address
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MarketplaceInfo(
                        icon = Icons.Default.Schedule,
                        label = "${restaurant.estimatedTimeMinutes} min"
                    )
                    MarketplaceInfo(
                        icon = Icons.Default.DeliveryDining,
                        label = "Distance-based delivery"
                    )
                }
            }
        }
    }
}
