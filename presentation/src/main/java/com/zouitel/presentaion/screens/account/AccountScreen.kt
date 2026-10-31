package com.zouitel.presentaion.screens.account

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storefront
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
import com.zouitel.presentaion.screens.delivery.DeliveryWorkplaceScreen
import com.zouitel.presentaion.screens.food.RestaurantWorkplaceScreen
import com.zouitel.presentaion.screens.ride.DriverWorkplaceScreen
import com.zouitel.presentaion.screens.shops.ShopWorkplaceScreen
import com.zouitel.presentaion.screens.payment.PaymentCenterScreen
import com.zouitel.presentaion.screens.wallet.MerchantEarningsScreen
import com.zouitel.presentaion.screens.wallet.CourierEarningsScreen
import com.zouitel.presentaion.screens.wallet.DriverEarningsScreen
import com.zouitel.presentaion.screens.wallet.PayoutAccountScreen
import com.zouitel.presentaion.screens.account.NotificationPreferencesScreen
import com.zouitel.presentaion.screens.account.HelpSupportScreen
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import kotlinx.coroutines.launch

class AccountScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()

        val screenModel = koinScreenModel<AccountScreenModel>()
        val user by screenModel.currentUser.collectAsState()
        val profile by screenModel.profile.collectAsState()

        val courierStatus = profile?.deliveryServices?.status ?: Status.NONE
        val driverStatus = profile?.driverService?.status ?: Status.NONE
        val restaurantStatus = profile?.foodStatus ?: Status.NONE
        val shopStatus = profile?.shopStatus ?: Status.NONE

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Account Center", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { navigator.push(SettingsScreen()) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // --- 1. Profile Banner Card ---
                item {
                    ElevatedCard(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val photoUrl = user?.photoURL
                            if (!photoUrl.isNullOrEmpty()) {
                                AsyncImage(
                                    model = photoUrl,
                                    contentDescription = "Profile photo",
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(68.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Person,
                                        contentDescription = "Profile photo",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.width(16.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = user?.displayName?.takeIf { it.isNotBlank() } ?: profile?.fullName ?: "Guest User",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = user?.email ?: user?.phoneNumber ?: "Not signed in",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = { /* Navigate to Edit Profile */ },
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Edit Profile",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                // --- 2. Business Portals (2x2 Super-App Grid) ---
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = "Business Portals",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            BusinessGridCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.LocalShipping,
                                title = "Delivery",
                                status = courierStatus,
                                onClick = { navigator.push(DeliveryWorkplaceScreen()) }
                            )
                            BusinessGridCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.DirectionsCar,
                                title = "Rides & Driver",
                                status = driverStatus,
                                onClick = { navigator.push(DriverWorkplaceScreen()) }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            BusinessGridCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Fastfood,
                                title = "Food & Dining",
                                status = restaurantStatus,
                                onClick = { navigator.push(RestaurantWorkplaceScreen()) }
                            )
                            BusinessGridCard(
                                modifier = Modifier.weight(1f),
                                icon = Icons.Filled.Storefront,
                                title = "Shop Portal",
                                status = shopStatus,
                                onClick = { navigator.push(ShopWorkplaceScreen()) }
                            )
                        }
                    }
                }

                // --- 3. Activity & Services Group ---
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Activity & Payments",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column {
                                GroupedAccountRow(Icons.Filled.History, "Orders & Ride History") { navigator.push(ActivityHistoryScreen()) }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                GroupedAccountRow(Icons.Filled.CreditCard, "Payments & Activity") { navigator.push(PaymentCenterScreen()) }
                                if (restaurantStatus == Status.APPROVED || shopStatus == Status.APPROVED) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    GroupedAccountRow(Icons.Filled.Storefront, "Merchant Earnings") { navigator.push(MerchantEarningsScreen()) }
                                }
                                if (courierStatus == Status.APPROVED) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    GroupedAccountRow(Icons.Filled.LocalShipping, "Courier Earnings") { navigator.push(CourierEarningsScreen()) }
                                }
                                if (driverStatus == Status.APPROVED) {
                                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                    GroupedAccountRow(Icons.Filled.DirectionsCar, "Driver Earnings") { navigator.push(DriverEarningsScreen()) }
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                GroupedAccountRow(Icons.Filled.AccountBalanceWallet, "Payout Account") { navigator.push(PayoutAccountScreen()) }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                GroupedAccountRow(Icons.Filled.NotificationsNone, "Notifications & Alerts") { navigator.push(NotificationPreferencesScreen()) }
                            }
                        }
                    }
                }

                // --- 4. System & Support Group ---
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Preferences & Support",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                        ) {
                            Column {
                                GroupedAccountRow(Icons.AutoMirrored.Filled.HelpOutline, "Help & Support") { navigator.push(HelpSupportScreen()) }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                GroupedAccountRow(Icons.Filled.Settings, "Application Settings") { navigator.push(SettingsScreen()) }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                GroupedAccountRow(
                                    icon = Icons.AutoMirrored.Filled.Logout,
                                    label = "Sign Out",
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
            }
        }
    }
}

@Composable
private fun BusinessGridCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    status: Status,
    onClick: () -> Unit
) {
    val (statusText, statusColor) = when (status) {
        Status.APPROVED -> "Active" to MaterialTheme.colorScheme.primary
        Status.PENDING -> "Pending" to MaterialTheme.colorScheme.tertiary
        Status.REJECTED -> "Declined" to MaterialTheme.colorScheme.error
        else -> "Apply" to MaterialTheme.colorScheme.outline
    }

    OutlinedCard(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Surface(
                    color = statusColor.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = statusText,
                        color = statusColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }
    }
}

@Composable
private fun GroupedAccountRow(
    icon: ImageVector,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(text = label, modifier = Modifier.weight(1f), color = tint, style = MaterialTheme.typography.bodyMedium)
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
    }
}