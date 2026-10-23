package com.zouitel.presentaion.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.CurrentScreen
import cafe.adriel.voyager.navigator.Navigator
import coil3.compose.AsyncImage
import com.google.firebase.auth.FirebaseAuth
import com.zouitel.presentaion.admin.PanelScreen
import com.zouitel.presentaion.common.AppTheme
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.screens.account.AccountScreen
import com.zouitel.presentaion.screens.account.AccountScreenModel
import com.zouitel.presentaion.screens.food.FoodOrdersScreen
import com.zouitel.presentaion.screens.food.RestaurantsScreen
import com.zouitel.presentaion.screens.ride.DriverDashboardScreen
import com.zouitel.presentaion.screens.ride.PassengerScreen
import com.zouitel.presentaion.screens.shops.ShopOrdersScreen
import com.zouitel.presentaion.screens.shops.ShopsScreen

private data class MainTab(
    val screen: Screen,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    /** Small green dot on the icon, used to show the driver is online. */
    val showLiveDot: Boolean = false,
)

class MainScreen() : Screen {

    @Composable
    override fun Content() {
        val accountScreen = koinScreenModel<AccountScreenModel>()
        val profile by accountScreen.profile.collectAsState()

        val tabs = remember(profile?.driverService, profile?.foodMode, profile?.shopMode, profile?.role) {
            listOf(
                // The first tab depends on whether the driver is online.
                if (profile?.driverService != null && profile?.driverService?.isOnline == true) {
                    MainTab(
                        screen = DriverDashboardScreen(),
                        label = "Drive",
                        selectedIcon = Icons.Filled.DirectionsCar,
                        unselectedIcon = Icons.Outlined.DirectionsCar,
                        showLiveDot = true,
                    )
                } else {
                    MainTab(PassengerScreen(), "Ride", Icons.Filled.DirectionsCar, Icons.Outlined.DirectionsCar)
                },
                if (profile?.foodMode == UserMode.MANAGER) {
                    MainTab(FoodOrdersScreen(), "Orders", Icons.Filled.Restaurant, Icons.Outlined.Restaurant)
                } else {
                    MainTab(RestaurantsScreen(), "Food", Icons.Filled.Restaurant, Icons.Outlined.Restaurant)
                },
                if (profile?.shopMode == UserMode.MANAGER) {
                    MainTab(ShopOrdersScreen(), "Orders", Icons.Filled.Storefront, Icons.Outlined.Storefront)
                } else {
                    MainTab(ShopsScreen(), "Shops", Icons.Filled.Storefront, Icons.Outlined.Storefront)
                },
                if (profile?.role == "ADMIN") MainTab(PanelScreen(), "Panel", Icons.Filled.AdminPanelSettings, Icons.Outlined.AdminPanelSettings) else null,
                MainTab(AccountScreen(), "Account", Icons.Filled.AccountCircle, Icons.Outlined.AccountCircle),
            ).filterNotNull()        }

        Navigator(tabs.first().screen) { navigator ->
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    BrandNavigationBar(
                        tabs = tabs,
                        isSelected = { navigator.lastItem::class == it.screen::class },
                        onSelect = { navigator.replaceAll(it.screen) },
                    )
                },
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    CurrentScreen()
                }
            }
        }
    }
}

/**
 * Bottom bar with five destinations. Only the selected one shows its label, in a
 * gradient pill that echoes the logo ring. That keeps five tabs readable on
 * narrow phones, where five fixed labels would be cramped.
 */
@Composable
private fun BrandNavigationBar(
    tabs: List<MainTab>,
    isSelected: (MainTab) -> Boolean,
    onSelect: (MainTab) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 16.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEach { tab ->
                val selected = isSelected(tab)
                BrandNavItem(
                    tab = tab,
                    selected = selected,
                    onClick = { if (!selected) onSelect(tab) },
                )
            }
        }
    }
}

@Composable
private fun BrandNavItem(
    tab: MainTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val contentColor by animateColorAsState(
        targetValue = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navContentColor",
    )

    Row(
        modifier = Modifier
            .animateContentSize()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .then(if (selected) Modifier.background(AppTheme.extras.gradient) else Modifier)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (selected) 16.dp else 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TabIcon(tab = tab, selected = selected, tint = contentColor)

        AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                text = tab.label,
                modifier = Modifier.padding(start = 8.dp),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        }
    }
}

@Composable
private fun TabIcon(tab: MainTab, selected: Boolean, tint: Color) {
    Box(contentAlignment = Alignment.TopEnd) {
        if (tab.screen is AccountScreen) {
            AccountTabIcon(selected = selected, tint = tint)
        } else {
            Icon(
                imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                // The label is visible when selected, so only describe the icon otherwise.
                contentDescription = if (selected) null else tab.label,
                tint = tint,
            )
        }

        if (tab.showLiveDot) {
            LiveDot(
                ringColor = if (selected) Color.White else MaterialTheme.colorScheme.surface,
                modifier = Modifier.offset(x = 4.dp, y = (-3).dp),
            )
        }
    }
}

@Composable
private fun LiveDot(ringColor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(ringColor, CircleShape)
            .padding(2.dp)
            .background(AppTheme.extras.success, CircleShape)
    )
}

@Composable
private fun AccountTabIcon(selected: Boolean, tint: Color) {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { user = it.currentUser }
        auth.addAuthStateListener(listener)
        onDispose { auth.removeAuthStateListener(listener) }
    }

    val photoUrl = user?.photoUrl

    if (photoUrl != null) {
        // White ring on the gradient pill when selected, a hairline when not.
        val ringColor by animateColorAsState(
            targetValue = if (selected) Color.White else MaterialTheme.colorScheme.outlineVariant,
            label = "avatarRing",
        )
        AsyncImage(
            model = photoUrl,
            contentDescription = if (selected) null else "Account",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .border(width = 1.5.dp, color = ringColor, shape = CircleShape),
        )
    } else {
        Icon(
            imageVector = if (selected) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
            contentDescription = if (selected) null else "Account",
            tint = tint,
        )
    }
}