package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Store
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.common.UserMode
import com.zouitel.presentaion.common.DriveTokenProvider
import com.zouitel.presentaion.common.PickedImage
import com.zouitel.presentaion.screens.account.AccountScreenModel
import com.zouitel.presentaion.common.ImagePicker
import com.zouitel.presentaion.screens.food.ImageUploadState
import androidx.compose.runtime.collectAsState
import coil3.compose.AsyncImage

class ShopWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopWorkplaceScreenModel() }
        val accountModel = koinScreenModel<AccountScreenModel>()
        val profile by accountModel.profile.collectAsState()
        val userShop by screenModel.userShop.collectAsState()
        val isOnline by screenModel.isOnline.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val imageUpload by screenModel.imageUpload.collectAsState()

        var selectedRoleMode by remember(profile?.shopMode) {
            mutableStateOf(accountModel.profile.value?.shopMode ?: UserMode.CLIENT)
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Shop Portal Choice", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (selectedRoleMode == UserMode.MANAGER && userShop != null) {
                            IconButton(onClick = {
                                navigator?.push(ShopRegistrationScreen(userShop))
                            }) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit Store")
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
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Select Portal Mode",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = selectedRoleMode == UserMode.CLIENT,
                        onClick = {
                            selectedRoleMode = UserMode.CLIENT
                            screenModel.updateShopMode(UserMode.CLIENT)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        icon = { Icon(Icons.Default.Person, contentDescription = null) }
                    ) {
                        Text("Client")
                    }

                    SegmentedButton(
                        selected = selectedRoleMode == UserMode.MANAGER,
                        onClick = {
                            selectedRoleMode = UserMode.MANAGER
                            screenModel.updateShopMode(UserMode.MANAGER)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        icon = { Icon(Icons.Default.Storefront, contentDescription = null) }
                    ) {
                        Text("Shop Manager")
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                when (selectedRoleMode) {
                    UserMode.CLIENT -> ClientRoleChoiceCard {
                        navigator?.push(ShopsScreen())
                    }
                    UserMode.MANAGER -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator()
                            } else {
                                when (val shop = userShop) {
                                    null -> ShopRegistrationForm(
                                        uploadState = imageUpload,
                                        onUploadImage = { image, token -> screenModel.uploadShopImage(image, token) },
                                        onUploadHandled = { screenModel.resetImageUpload() },
                                        onSubmit = { name, desc, cat, phone, addr, img ->
                                            screenModel.submitShopApplication(
                                                name,
                                                desc,
                                                cat,
                                                phone,
                                                addr,
                                                img,
                                                isOnline
                                            )
                                        }
                                    )
                                    else -> when (shop.status) {
                                        Status.PENDING -> PendingVerificationView(shop)
                                        Status.REJECTED -> RejectedApplicationView(shop)
                                        Status.APPROVED -> ApprovedDashboardView(
                                            shop = shop,
                                            isOnline = isOnline,
                                            onOnlineChanged = { screenModel.updateShopOnlineStatus(shop.id, it) },
                                            onOpenDashboard = { navigator?.push(ShopDashboardScreen(shop.id)) }
                                        )
                                        else -> {}
                                    }
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    @Composable
    private fun ClientRoleChoiceCard(onExploreShops: () -> Unit) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onExploreShops),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Storefront,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Text("Browse Stores as Client", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Discover nearby verified retail stores, inspect product catalogs, add products to your demand card, and trigger home delivery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Button(
                    onClick = onExploreShops,
                    modifier = Modifier.fillMaxWidth().height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Explore Verified Stores", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun ShopRegistrationForm(
        uploadState: ImageUploadState,
        onUploadImage: (PickedImage, DriveTokenProvider) -> Unit,
        onUploadHandled: () -> Unit,
        onSubmit: (String, String, String, String, String, String) -> Unit
    ) {
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        var category by remember { mutableStateOf("Grocery & Supermarket") }
        var phone by remember { mutableStateOf("") }
        var address by remember { mutableStateOf("") }
        var imageUrl by remember { mutableStateOf("") }

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
                Text("Register Store Business", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                Text(
                    "Submit your store details for admin verification.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Store Name") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Store Description") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Store Category") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Business Phone Number") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Physical Store Address") },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                ImagePicker(
                    imageUrl = imageUrl,
                    uploadState = uploadState,
                    onImagePicked = onUploadImage,
                    emptyLabel = "Add a store photo or logo",
                    contentDescription = "Store photo"
                )
            }

            item {
                Button(
                    onClick = { onSubmit(name, description, category, phone, address, imageUrl) },
                    enabled = name.isNotBlank() && phone.isNotBlank() && address.isNotBlank() && !isUploading,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Text(if (isUploading) "Uploading photo…" else "Submit & Update Profile", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    @Composable
    private fun PendingVerificationView(shop: Shop) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.HourglassEmpty,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Verification Pending", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text(
                "Your application for '${shop.name}' is under review by the administrator.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    @Composable
    private fun RejectedApplicationView(shop: Shop) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text("Application Rejected", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
            Text(
                "Your shop application for '${shop.name}' was declined.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }

    @Composable
    private fun ApprovedDashboardView(
        shop: Shop,
        isOnline: Boolean,
        onOnlineChanged: (Boolean) -> Unit,
        onOpenDashboard: () -> Unit
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Store, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text("Store Status", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                Text(
                                    text = if (isOnline) "Online & Accepting Orders" else "Offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        Switch(checked = isOnline, onCheckedChange = onOnlineChanged)
                    }
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (shop.imageUrl.isNotBlank()) {
                            AsyncImage(
                                model = shop.imageUrl,
                                contentDescription = "Store photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }

                        Text(text = shop.name, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        Text(text = "Category: ${shop.category}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Description: ${shop.description.ifBlank { "N/A" }}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(text = "Address: ${shop.address}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Phone: ${shop.phone}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            item {
                Button(
                    onClick = onOpenDashboard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) {
                    Icon(Icons.Default.Store, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Store Manager Dashboard", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}