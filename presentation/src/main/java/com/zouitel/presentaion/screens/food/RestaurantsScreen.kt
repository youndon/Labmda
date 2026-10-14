package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Restaurants") }, 
                actions = {
                    if (profile?.foodMode == UserMode.MANAGER || profile?.foodMode == UserMode.COURIER) { 
                        if (profile?.foodMode == UserMode.MANAGER) { 
                            IconButton(onClick = { navigator?.push(FoodDashboardScreen()) }) { 
                                Icon(
                                    imageVector = Icons.Default.Dashboard,
                                    contentDescription = "Dashboard"
                                )
                            }
                        }

                        if (profile?.foodMode == UserMode.COURIER) { 
                            IconButton(onClick = { navigator?.push(DeliveryDashboardScreen()) }) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsBike,
                                    contentDescription = "Delivery Dashboard"
                                )
                            }
                        }
                    } else {
                        IconButton(onClick = { navigator?.push(FoodActivitiesScreen()) }) { 
                            Icon(
                                imageVector = Icons.Default.ReceiptLong,
                                contentDescription = "Orders"
                            )
                        }
                    }

                    IconButton(onClick = { navigator?.push(FoodSettingsScreen()) }) { 
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Food Settings"
                        )
                    }
                }
                )
            }
        ) { paddingValues ->
            if (restaurants.isEmpty()) { 
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No restaurants available right now.") 
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(restaurants, key = { it.id }) { restaurant -> 
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
    elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) { 
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = restaurant.name, style = MaterialTheme.typography.titleLarge) 

                // Online / Offline Status Badge
                Badge(
                    containerColor = if (restaurant.isOpen) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ) {
                    Text(
                        text = if (restaurant.isOpen) "Online" else "Offline",
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp)) 

            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth() 
            ) {
                Text(
                    text = "Delivery: $${"%.2f".format(restaurant.deliveryFee)}",
                style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "${restaurant.estimatedTimeMinutes} mins", 
                style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}