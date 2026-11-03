package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
        val profile by accountModel.profile.collectAsState()

        var searchQuery by rememberSaveable { mutableStateOf("") }
        var selectedAvailability by rememberSaveable { mutableStateOf("All") }

        val visibleRestaurants = remember(restaurants, searchQuery, selectedAvailability) {
            restaurants.filter { restaurant ->
                val matchesQuery = searchQuery.isBlank() ||
                    restaurant.name.contains(searchQuery.trim(), ignoreCase = true) ||
                    restaurant.address.contains(searchQuery.trim(), ignoreCase = true)
                val matchesAvailability = when (selectedAvailability) {
                    "Open now" -> restaurant.isOpen
                    "Closed" -> !restaurant.isOpen
                    else -> true
                }
                matchesQuery && matchesAvailability
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Explore Restaurants", fontWeight = FontWeight.Bold) },
                    actions = {
                        if (profile?.foodMode == UserMode.MANAGER || profile?.foodMode == UserMode.COURIER) {
                            if (profile?.foodMode == UserMode.MANAGER) {
                                IconButton(onClick = { navigator?.push(FoodOrdersScreen()) }) {
                                    Icon(Icons.Default.Dashboard, contentDescription = "Dashboard")
                                }
                            }
                            if (profile?.foodMode == UserMode.COURIER) {
                                IconButton(onClick = { navigator?.push(DeliveryDashboardScreen()) }) {
                                    Icon(Icons.Default.DirectionsBike, contentDescription = "Delivery Dashboard")
                                }
                            }
                        } else {
                            IconButton(onClick = { navigator?.push(FoodActivitiesScreen()) }) {
                                Icon(Icons.Default.ReceiptLong, contentDescription = "Orders")
                            }
                        }
                        IconButton(onClick = { navigator?.push(FoodSettingsScreen()) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Food Settings")
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Find your next favorite meal",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Browse nearby kitchens and discover what is available today.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        placeholder = { Text("Search restaurants or locations") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear search")
                                }
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf("All", "Open now", "Closed").forEach { filter ->
                            FilterChip(
                                selected = selectedAvailability == filter,
                                onClick = { selectedAvailability = filter },
                                label = { Text(filter) },
                                shape = RoundedCornerShape(12.dp)
                            )
                        }
                    }
                    if (restaurants.isNotEmpty()) {
                        Text(
                            text = "${visibleRestaurants.size} restaurant${if (visibleRestaurants.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                when {
                    restaurants.isEmpty() -> EmptyRestaurantsState(
                        modifier = Modifier.weight(1f),
                        title = "No restaurants yet",
                        message = "Restaurants will appear here when they become available."
                    )
                    visibleRestaurants.isEmpty() -> EmptyRestaurantsState(
                        modifier = Modifier.weight(1f),
                        title = "No matches found",
                        message = "Try another restaurant name or change the availability filter."
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
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
}

@Composable
private fun EmptyRestaurantsState(
    modifier: Modifier = Modifier,
    title: String,
    message: String
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.Restaurant,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RestaurantCardItem(
    restaurant: Restaurant,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column {
            if (restaurant.imageUrl.isNotBlank()) {
                AsyncImage(
                    model = restaurant.imageUrl,
                    contentDescription = restaurant.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(112.dp)
                        .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Restaurant,
                        contentDescription = null,
                        modifier = Modifier.size(42.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Text(
                        text = restaurant.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.width(10.dp))
                    Surface(
                        color = if (restaurant.isOpen) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = if (restaurant.isOpen) "Open" else "Closed",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (restaurant.isOpen) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                if (restaurant.address.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            restaurant.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Delivery fee", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "₱${"%.2f".format(restaurant.deliveryFee)}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            "${restaurant.estimatedTimeMinutes} min",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }
}
