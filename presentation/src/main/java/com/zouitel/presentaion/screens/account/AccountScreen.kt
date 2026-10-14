package com.zouitel.presentaion.screens.account

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.screens.delivery.DeliveryWorkplaceScreen
import com.zouitel.presentaion.screens.food.RestaurantWorkplaceScreen
import com.zouitel.presentaion.screens.freight.FreightWorkplaceScreen
import com.zouitel.presentaion.screens.ride.DriverWorkplaceScreen
import com.zouitel.presentaion.screens.shops.ShopWorkplaceScreen
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.launch

class AccountScreen : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        val screenModel = koinScreenModel<AccountScreenModel>()
        val user by screenModel.currentUser.collectAsState()
        val profile by screenModel.profile.collectAsState()

        val courierStatus = remember(profile?.deliveryServices?.status) {
            profile?.deliveryServices?.status ?: Status.NONE
        }
        val driverStatus = remember(profile?.driverService?.status) {
            profile?.driverService?.status ?: Status.NONE
        }
        val restaurantStatus = remember(profile?.foodStatus) { profile?.foodStatus ?: Status.NONE }
        val shopStatus = remember(profile?.shopStatus) { profile?.shopStatus ?: Status.NONE }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Account") },
                    actions = {
                        IconButton(onClick = { navigator.push(SettingsScreen()) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 20.dp)
            ) {
                // --- Profile header --- 
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val photoUrl = user?.photoURL
                    if (!photoUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = "Profile photo",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Person,
                                contentDescription = "Profile photo",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(32.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    Column {
                        Text(
                            text = user?.displayName?.takeIf { it.isNotBlank() } ?: profile?.fullName ?: "Guest",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = user?.email ?: user?.phoneNumber ?: "Not signed in",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider()

                // --- Businesses --- 
                Text(
                    text = "Businesses",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))

                // --- Delivery Workplace ---
                AccountStatusRow(
                    icon = Icons.Filled.Work,
                    label = "Delivery Workplace",
                    statusText = when (courierStatus) {
                        Status.APPROVED -> "Active"
                        Status.PENDING -> "Pending Review"
                        Status.REJECTED -> "Declined"
                        else -> "Apply"
                    },
                    statusColor = when (courierStatus) {
                        Status.APPROVED -> MaterialTheme.colorScheme.primary
                        Status.PENDING -> MaterialTheme.colorScheme.tertiary
                        Status.REJECTED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                ) {
                    navigator.push(DeliveryWorkplaceScreen()) 
                }

                // --- Freight Workplace ---
                AccountRow(Icons.Filled.LocalShipping, "Freight Workplace") {
                    navigator.push(FreightWorkplaceScreen()) 
                }

                // --- Driver Workplace ---
                AccountStatusRow(
                    icon = Icons.Filled.DirectionsCar,
                    label = "Driver Workplace",
                    statusText = when (driverStatus) {
                        Status.APPROVED -> "Active"
                        Status.PENDING -> "Pending Review"
                        Status.REJECTED -> "Declined"
                        else -> "Apply"
                    },
                    statusColor = when (driverStatus) {
                        Status.APPROVED -> MaterialTheme.colorScheme.primary
                        Status.PENDING -> MaterialTheme.colorScheme.tertiary
                        Status.REJECTED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                ) {
                    navigator.push(DriverWorkplaceScreen()) 
                }

                // --- Food Workplace --- 
                AccountStatusRow(
                    icon = Icons.Filled.Fastfood,
                    label = "Food Workplace",
                    statusText = when (restaurantStatus) {
                        Status.APPROVED -> "Active"
                        Status.PENDING -> "Pending Review"
                        Status.REJECTED -> "Declined"
                        else -> "Apply"
                    },
                    statusColor = when (restaurantStatus) {
                        Status.APPROVED -> MaterialTheme.colorScheme.primary
                        Status.PENDING -> MaterialTheme.colorScheme.tertiary
                        Status.REJECTED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                ) {
                    navigator.push(RestaurantWorkplaceScreen()) 
                }

                // --- Shop Workplace --- 
                AccountStatusRow(
                    icon = Icons.Filled.Storefront,
                    label = "Shop Workplace",
                    statusText = when (shopStatus) {
                        Status.APPROVED -> "Active"
                        Status.PENDING -> "Pending Review"
                        Status.REJECTED -> "Declined"
                        else -> "Apply"
                    },
                    statusColor = when (shopStatus) {
                        Status.APPROVED -> MaterialTheme.colorScheme.primary
                        Status.PENDING -> MaterialTheme.colorScheme.tertiary
                        Status.REJECTED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.outline
                    }
                ) {
                    navigator.push(ShopWorkplaceScreen()) 
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                // --- Account actions --- 
                AccountRow(Icons.Filled.History, "Ride history") { /* TODO */ }
                AccountRow(Icons.Filled.CreditCard, "Payment methods") { /* TODO */ }
                AccountRow(Icons.Filled.NotificationsNone, "Notifications") { /* TODO */ }
                AccountRow(Icons.Filled.HelpOutline, "Help & support") { /* TODO */ }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()

                AccountRow(Icons.Filled.Settings, "Settings") { navigator.push(SettingsScreen()) } 
                AccountRow(
                    icon = Icons.Filled.Logout,
                    label = "Sign out",
                    tint = MaterialTheme.colorScheme.error
                ) {
                    coroutineScope.launch {
                        Firebase.auth.signOut()
                    }
                    Toast.makeText(context, "Signed out", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

@Composable
private fun AccountRow(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Spacer(Modifier.width(16.dp))
        Text(text = label, modifier = Modifier.weight(1f), color = tint)
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccountStatusRow(
    icon: ImageVector,
    label: String,
    statusText: String,
    statusColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(16.dp))
        Text(text = label, modifier = Modifier.weight(1f))

        Badge(containerColor = statusColor.copy(alpha = 0.15f)) {
            Text(
                text = statusText,
                color = statusColor,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}