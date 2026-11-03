package com.zouitel.presentaion.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DriveEta
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator

class PanelScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Admin workspace", fontWeight = FontWeight.Bold)
                            Text(
                                "LABMDA CONTROL CENTER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Operations overview",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Manage provider applications, service operations and the platform's financial controls from one place.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "Open a section to load its latest server-backed information.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                item {
                    AdminSection(
                        title = "Providers & services",
                        description = "Review applications and manage the businesses and people operating on Labmda."
                    ) {
                        AdminNavigationCard(
                            title = "Driver applications",
                            description = "Review driver profiles and approval status.",
                            icon = { Icon(Icons.Default.DriveEta, contentDescription = null) }
                        ) { navigator?.push(AdminDriverScreen()) }
                        AdminNavigationCard(
                            title = "Courier applications",
                            description = "Review delivery providers and identity documents.",
                            icon = { Icon(Icons.Default.DirectionsBike, contentDescription = null) }
                        ) { navigator?.push(DeliveryScreen()) }
                        AdminNavigationCard(
                            title = "Restaurant applications",
                            description = "Review restaurant onboarding requests.",
                            icon = { Icon(Icons.Default.Restaurant, contentDescription = null) }
                        ) { navigator?.push(AdminRestaurantScreen()) }
                        AdminNavigationCard(
                            title = "Restaurant directory",
                            description = "Browse restaurants already registered on the platform.",
                            icon = { Icon(Icons.Default.Restaurant, contentDescription = null) }
                        ) { navigator?.push(Restaurants()) }
                        AdminNavigationCard(
                            title = "Shop applications",
                            description = "Review shop onboarding and provider details.",
                            icon = { Icon(Icons.Default.Storefront, contentDescription = null) }
                        ) { navigator?.push(AdminShopsScreen()) }
                        AdminNavigationCard(
                            title = "Freight applications",
                            description = "Review freight operators and submitted documents.",
                            icon = { Icon(Icons.Default.LocalShipping, contentDescription = null) }
                        ) { navigator?.push(AdminFreightScreen()) }
                    }
                }

                item {
                    AdminSection(
                        title = "Finance & platform controls",
                        description = "Review ledger-backed figures and manage payment, payout and pricing workflows."
                    ) {
                        AdminNavigationCard(
                            title = "Financial overview",
                            description = "Platform revenue, provider balances, payment volume and payout pipeline.",
                            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) }
                        ) { navigator?.push(AdminFinanceOverviewScreen()) }
                        AdminNavigationCard(
                            title = "Financial operations",
                            description = "Payments, refunds, balances, audit history and discrepancies.",
                            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) }
                        ) { navigator?.push(AdminFinancialOperationsScreen()) }
                        AdminNavigationCard(
                            title = "Payment settings",
                            description = "Configure the payment methods and services exposed by checkout.",
                            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) }
                        ) { navigator?.push(AdminPaymentSettingsScreen()) }
                        AdminNavigationCard(
                            title = "Provider payouts",
                            description = "Review payout requests and record verified transfer references.",
                            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) }
                        ) { navigator?.push(AdminPayoutScreen()) }
                        AdminNavigationCard(
                            title = "Pricing & commissions",
                            description = "Manage ride, delivery and marketplace pricing rules.",
                            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) }
                        ) { navigator?.push(AdminPricingScreen()) }
                        AdminNavigationCard(
                            title = "Settlement reconciliation",
                            description = "Match expected settlements against recorded bank statement lines.",
                            icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) }
                        ) { navigator?.push(AdminSettlementReconciliationScreen()) }
                    }
                }

                item {
                    Text(
                        "Financial actions and provider approvals remain subject to server-side authorization. Figures are loaded by their respective admin screens.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminSection(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        content()
    }
}

@Composable
private fun AdminNavigationCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                shape = RoundedCornerShape(13.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.size(46.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "›",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
