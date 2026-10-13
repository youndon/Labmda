package com.zouitel.presentaion.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.zouitel.presentaion.admin.PanelScreen
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.screens.account.AccountScreen
import com.zouitel.presentaion.screens.account.AccountScreenModel
import com.zouitel.presentaion.screens.food.FoodDashboardScreen
import com.zouitel.presentaion.screens.food.RestaurantsScreen
import com.zouitel.presentaion.screens.ride.DriverDashboardScreen
import com.zouitel.presentaion.screens.ride.PassengerScreen
import com.zouitel.presentaion.screens.shops.ShopOrdersScreen
import com.zouitel.presentaion.screens.shops.ShopsScreen
import com.zouitel.presentaion.screens.shops.ShopsScreenModel

private data class MainTab(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

class MainScreen() : Screen {

    @Composable
    override fun Content() {
        val accountScreen = koinScreenModel<AccountScreenModel>()
        val shopModel = koinScreenModel<ShopsScreenModel>()
        val profile by accountScreen.profile.collectAsState()

        val tabs = remember(profile?.driveMode, profile?.foodMode, profile?.shopMode) {
            listOf(
                // Dynamically assign the first tab based on the selected mode
                if (profile?.driveMode == UserMode.DRIVER) {
                    MainTab(DriverDashboardScreen(), "Drive", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar)
                } else {
                    MainTab(PassengerScreen(), "Passenger", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar)
                },
                if (profile?.foodMode == UserMode.MANAGER) {
                    MainTab(FoodDashboardScreen(), "Orders", Icons.Filled.Restaurant, Icons.Outlined.Restaurant)
                } else {
                    MainTab(RestaurantsScreen(), "Food", Icons.Filled.Restaurant, Icons.Outlined.Restaurant)
                },
                if (profile?.shopMode == UserMode.MANAGER) {
                    MainTab(ShopOrdersScreen(), "Orders", Icons.Filled.Storefront, Icons.Outlined.Storefront)
                } else {
                    MainTab(ShopsScreen(), "Shops", Icons.Filled.Storefront, Icons.Outlined.Storefront)
                },
                MainTab(PanelScreen(), "Panel", Icons.Filled.AdminPanelSettings, Icons.Outlined.AdminPanelSettings),
                MainTab(AccountScreen(), "Account", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
            )
        }

        Navigator(tabs.first().screen) { navigator ->
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar {
                        tabs.forEach { tab ->
                            val selected = navigator.lastItem::class == tab.screen::class
                            NavigationBarItem(
                                selected = selected,
                                onClick = { if (!selected) navigator.replaceAll(tab.screen) },
                                icon = {
                                    if (tab.screen is AccountScreen) {
                                        AccountTabIcon(selected = selected)
                                    } else {
                                        Icon(
                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                            contentDescription = tab.label,
                                        )
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    CurrentScreen()
                }
            }
        }
    }
}

@Composable
private fun AccountTabIcon(selected: Boolean) {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val photoUrl = user?.photoUrl

    if (photoUrl != null) {
        AsyncImage(
            model = photoUrl,
            contentDescription = "Account",
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(
                    width = 1.5.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = CircleShape,
                )
        )
    } else {
        Icon(
            imageVector = if (selected) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
            contentDescription = "Account",
        )
    }
}