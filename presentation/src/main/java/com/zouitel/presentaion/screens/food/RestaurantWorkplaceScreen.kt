package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Fastfood
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.screens.cash.MerchantCashSettlementScreen
import com.zouitel.presentaion.screens.finance.ProviderEarningsScreen
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.AppCard
import com.zouitel.presentaion.common.AppCardContent
import com.zouitel.presentaion.common.AppPrimaryButton
import com.zouitel.presentaion.common.AppSectionTitle
import com.zouitel.presentaion.common.AppSpacing
import com.zouitel.presentaion.common.UserMode
import androidx.compose.material.icons.filled.LocationOn
import com.google.android.gms.maps.model.LatLng
import com.zouitel.presentaion.common.LocationPickerDialog
import com.zouitel.presentaion.common.DriveTokenProvider
import com.zouitel.presentaion.common.ImagePicker
import com.zouitel.presentaion.common.PickedImage
import coil3.compose.AsyncImage

class RestaurantWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { RestaurantWorkplaceScreenModel() }
        val profile by screenModel.profile.collectAsState()
        val restaurantInfo by screenModel.restaurantInfo.collectAsState()
        val meals by screenModel.meals.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val imageUpload by screenModel.imageUpload.collectAsState()

        var selectedRoleMode by remember(profile.foodMode) {
            mutableStateOf(profile.foodMode)
        }

        var isEditing by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (isEditing) "Edit Restaurant Info" else "Food Portal Mode", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isEditing) isEditing = false else navigator?.pop()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (selectedRoleMode != UserMode.CLIENT && profile.foodStatus == Status.APPROVED && !isEditing) {
                            IconButton(onClick = { isEditing = true }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Restaurant")
                            }
                            IconButton(onClick = { navigator?.push(MerchantCashSettlementScreen()) }) {
                                Icon(Icons.Default.AccountBalanceWallet, contentDescription = "Cash settlements")
                            }
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(AppSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)
            ) {
                if (!isEditing) {
                    AppSectionTitle(
                        title = "Choose your workspace",
                        subtitle = "Switch between ordering food and managing your restaurant."
                    )

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = selectedRoleMode == UserMode.CLIENT,
                            onClick = {
                                selectedRoleMode = UserMode.CLIENT
                                screenModel.updateFoodMode(UserMode.CLIENT)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            icon = { Icon(Icons.Default.Person, contentDescription = null) }
                        ) {
                            Text("Client")
                        }

                        SegmentedButton(
                            selected = selectedRoleMode == UserMode.MANAGER || selectedRoleMode != UserMode.CLIENT,
                            onClick = {
                                selectedRoleMode = UserMode.MANAGER
                                screenModel.updateFoodMode(UserMode.MANAGER)
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            icon = { Icon(Icons.Default.Restaurant, contentDescription = null) }
                        ) {
                            Text("Manager")
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }

                Box(modifier = Modifier.weight(1f)) {
                    when {
                        isEditing -> {
                            RestaurantApplicationForm(
                                initialName = restaurantInfo.name,
                                initialImage = restaurantInfo.imageUrl,
                                initialFee = restaurantInfo.deliveryFee.toString(),
                                initialTime = restaurantInfo.estimatedTimeMinutes.toString(),
                                initialPhone = restaurantInfo.managerPhone.ifBlank { profile.phoneNumber ?: "" },
                                initialAddress = restaurantInfo.address,
                                initialLat = restaurantInfo.latitude,
                                initialLng = restaurantInfo.longitude,
                                submitButtonText = "Save Changes",
                                uploadState = imageUpload,
                                onUploadImage = { image, token -> screenModel.uploadRestaurantImage(image, token) },
                                onUploadHandled = { screenModel.resetImageUpload() },
                                onSubmit = { name, img, fee, time, phone, address, lat, lng ->
                                    screenModel.submitApplication(name, img, fee, time, phone, address, lat, lng)
                                    isEditing = false
                                }
                            )
                        }
                        selectedRoleMode == UserMode.CLIENT -> ClientModeCard {
                            navigator?.push(RestaurantsScreen())
                        }
                        else -> {
                            if (isLoading) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                when (profile.foodStatus) {
                                    Status.NONE -> RestaurantApplicationForm(
                                        initialPhone = profile.phoneNumber ?: "",
                                        uploadState = imageUpload,
                                        onUploadImage = { image, token -> screenModel.uploadRestaurantImage(image, token) },
                                        onUploadHandled = { screenModel.resetImageUpload() },
                                        onSubmit = { name, img, fee, time, phone, address, lat, lng ->
                                            screenModel.submitApplication(name, img, fee, time, phone, address, lat, lng)
                                        }
                                    )
                                    Status.PENDING -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PendingStatusView() }
                                    Status.REJECTED -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { RejectedStatusView() }
                                    Status.APPROVED -> ApprovedWorkplaceView(
                                        restaurantInfo = restaurantInfo,
                                        mealCount = meals.size,
                                        isOnline = profile.isFoodServiceOnline,
                                        onOnlineChanged = { screenModel.updateOnlineStatus(it) },
                                        onManageMenu = {
                                            navigator?.push(
                                                RestaurantDashboardScreen(restaurantInfo.id.ifBlank { profile.uid })
                                            )
                                        },
                                        onOpenEarnings = { navigator?.push(ProviderEarningsScreen()) }
                                    )
                                    else -> {
                                        Text("Unknown Status")
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
    private fun ClientModeCard(onExploreFood: () -> Unit) {
        AppCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExploreFood),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.lg)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Fastfood,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text("Client Mode Active", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Browse restaurants, manage your food cart, and place orders in real-time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                AppPrimaryButton(
                    text = "Explore Food Catalog",
                    onClick = onExploreFood,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    private fun RestaurantApplicationForm(
        initialName: String = "",
        initialImage: String = "",
        initialFee: String = "",
        initialTime: String = "",
        initialPhone: String,
        initialAddress: String = "",
        initialLat: Double = 0.0,
        initialLng: Double = 0.0,
        submitButtonText: String = "Submit Application",
        uploadState: ImageUploadState,
        onUploadImage: (PickedImage, DriveTokenProvider) -> Unit,
        onUploadHandled: () -> Unit,
        onSubmit: (String, String, Double, Int, String, String, Double, Double) -> Unit
    ) {
        var restaurantName by remember { mutableStateOf(initialName) }
        var imageUrl by remember { mutableStateOf(initialImage) }
        var deliveryFee by remember { mutableStateOf(initialFee) }
        var estimatedTime by remember { mutableStateOf(initialTime) }
        var managerPhone by remember { mutableStateOf(initialPhone) }
        var address by remember { mutableStateOf(initialAddress) }
        var location by remember {
            mutableStateOf(if (initialLat != 0.0 || initialLng != 0.0) LatLng(initialLat, initialLng) else null)
        }
        var showPicker by remember { mutableStateOf(false) }
        val isUploading = uploadState is ImageUploadState.Uploading

        LaunchedEffect(uploadState) {
            if (uploadState is ImageUploadState.Success) {
                imageUrl = uploadState.url
                onUploadHandled()
            }
        }

        DisposableEffect(Unit) {
            onDispose { onUploadHandled() }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Text("Restaurant Details", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text("Provide your restaurant information for management and review.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                OutlinedTextField(
                    value = restaurantName, onValueChange = { restaurantName = it },
                    label = { Text("Restaurant Name") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                ImagePicker(
                    imageUrl = imageUrl,
                    uploadState = uploadState,
                    onImagePicked = onUploadImage,
                    emptyLabel = "Add a restaurant photo",
                    contentDescription = "Restaurant photo"
                )
            }
            item {
                OutlinedTextField(
                    value = managerPhone, onValueChange = { managerPhone = it },
                    label = { Text("Manager Phone Number") },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = deliveryFee, onValueChange = { deliveryFee = it },
                    label = { Text("Delivery Fee (₱)") },
                    supportingText = { Text("Enter the fee in Philippine pesos (PHP).") },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = estimatedTime, onValueChange = { estimatedTime = it },
                    label = { Text("Estimated Time (Minutes)") },
                    supportingText = { Text("Enter the estimated preparation time in minutes.") },
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedButton(
                    onClick = { showPicker = true },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.LocationOn, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (location == null) "Set restaurant location on map" else "Change restaurant location")
                }
            }

            if (location != null) {
                item {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        label = { Text("Restaurant address (couriers pick up here)") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Button(
                    onClick = {
                        val loc = location ?: return@Button
                        onSubmit(
                            restaurantName,
                            imageUrl,
                            deliveryFee.toDoubleOrNull() ?: 0.0,
                            estimatedTime.toIntOrNull() ?: 20,
                            managerPhone,
                            address,
                            loc.latitude,
                            loc.longitude
                        )
                    },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    enabled = restaurantName.isNotBlank() && managerPhone.isNotBlank() &&
                            location != null && address.isNotBlank() && !isUploading
                ) {
                    Text(if (isUploading) "Uploading photo…" else submitButtonText, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (showPicker) {
            LocationPickerDialog(
                title = "Set restaurant location",
                confirmLabel = "Confirm location",
                initial = location,
                onConfirm = { point, resolved ->
                    location = point
                    address = resolved
                    showPicker = false
                },
                onDismiss = { showPicker = false }
            )
        }
    }

    @Composable
    private fun PendingStatusView() {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.HourglassEmpty, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Application Pending", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text("An admin is reviewing your restaurant application. Please check back later.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }

    @Composable
    private fun RejectedStatusView() {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Application Rejected", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text("Unfortunately, your restaurant application was not approved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }

    @Composable
    private fun ApprovedWorkplaceView(
        restaurantInfo: ManagedRestaurantInfo,
        mealCount: Int,
        isOnline: Boolean,
        onOnlineChanged: (Boolean) -> Unit,
        onManageMenu: () -> Unit,
        onOpenEarnings: () -> Unit
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.lg),
            contentPadding = PaddingValues(bottom = AppSpacing.xxl)
        ) {
            item {
                AppSectionTitle(
                    title = "Restaurant overview",
                    subtitle = "A quick view of your storefront, availability, and menu."
                )
            }

            item {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column {
                        if (restaurantInfo.imageUrl.isNotBlank()) {
                            AsyncImage(
                                model = restaurantInfo.imageUrl,
                                contentDescription = "Restaurant photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(176.dp),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(AppSpacing.lg),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = restaurantInfo.name.ifBlank { "My Restaurant" },
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = if (isOnline) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text(
                                        text = if (isOnline) "OPEN" else "CLOSED",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOnline) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Text(
                                text = restaurantInfo.address.ifBlank { "Add your pickup address to complete your storefront profile." },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (restaurantInfo.managerPhone.isNotBlank()) {
                                Text(
                                    text = "Manager contact · ${restaurantInfo.managerPhone}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            item {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                        ) {
                            Text(
                                "Order availability",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (isOnline) "Customers can place orders from your restaurant."
                                else "Your restaurant is hidden from new order requests.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isOnline,
                            onCheckedChange = onOnlineChanged
                        )
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    RestaurantMetricCard(
                        modifier = Modifier.weight(1f),
                        label = "Menu items",
                        value = mealCount.toString(),
                        supporting = if (mealCount == 0) "Add your first meal" else "Listed meals",
                        icon = Icons.Default.Fastfood
                    )
                    RestaurantMetricCard(
                        modifier = Modifier.weight(1f),
                        label = "Prep time",
                        value = "${restaurantInfo.estimatedTimeMinutes} min",
                        supporting = "Estimated",
                        icon = Icons.Default.HourglassEmpty
                    )
                }
            }

            item {
                AppCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg),
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                    ) {
                        Text(
                            "Delivery information",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "₱${"%.2f".format(restaurantInfo.deliveryFee)}",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            "Delivery fee shown in your restaurant profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                AppSectionTitle(
                    title = "Manage your business",
                    subtitle = "Keep your menu current and review your provider balance."
                )
            }

            item {
                AppCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onManageMenu),
                    shape = MaterialTheme.shapes.extraLarge
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(AppSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Fastfood,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Manage menu",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (mealCount == 0) "Create your first menu item"
                                else "Review and update your $mealCount menu items",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Open menu",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                AppPrimaryButton(
                    text = "Earnings & Payouts",
                    onClick = onOpenEarnings,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    private fun RestaurantMetricCard(
        modifier: Modifier = Modifier,
        label: String,
        value: String,
        supporting: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector
    ) {
        AppCard(
            modifier = modifier,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.xs)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

}