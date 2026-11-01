package com.zouitel.presentaion.screens.shops

import com.zouitel.presentaion.model.formatPhpPesos
import com.zouitel.presentaion.model.formatPhpCentavos

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import coil3.compose.AsyncImage
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.DriveTokenProvider
import com.zouitel.presentaion.common.PickedImage
import com.zouitel.presentaion.common.ImagePicker
import com.zouitel.presentaion.screens.food.ImageUploadState

data class ShopDashboardScreen(val shopId: String) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopDashboardScreenModel(shopId) }
        val products by screenModel.products.collectAsState()
        val metrics by screenModel.metrics.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()
        val imageUpload by screenModel.imageUpload.collectAsState()

        var showAddEditDialog by remember { mutableStateOf(false) }
        var selectedProductToEdit by remember { mutableStateOf<Product?>(null) }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Shop dashboard", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = {
                        selectedProductToEdit = null
                        showAddEditDialog = true
                    },
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Product")
                }
            }
        ) { paddingValues ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(24.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "Your shop at a glance",
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "Track orders, review sales and keep your product catalogue up to date.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                                    )
                                    Text(
                                        "${products.size} products in your catalogue",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                            Text("Order activity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SummaryTile("New", metrics.newOrders, Modifier.weight(1f))
                                SummaryTile("Preparing", metrics.preparingOrders, Modifier.weight(1f))
                                SummaryTile("On delivery", metrics.outForDeliveryOrders, Modifier.weight(1f))
                            }
                            Text("Sales performance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SalesTile("Today · ${metrics.todayOrders} orders", metrics.todaySales, Modifier.weight(1f))
                                SalesTile("Last 7 days", metrics.weekSales, Modifier.weight(1f))
                            }
                            OutlinedButton(
                                onClick = { navigator?.push(ShopOrdersScreen()) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Open order queue")
                            }
                            val lowStock = products.filter { it.stockQuantity <= 5 }
                            if (lowStock.isNotEmpty()) {
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Stock attention · ${lowStock.size} products", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.Bold)
                                        lowStock.take(4).forEach { product ->
                                            Text(
                                                if (product.stockQuantity <= 0) "• ${product.name} — out of stock" else "• ${product.name} — ${product.stockQuantity} left",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onErrorContainer
                                            )
                                        }
                                    }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text("Product catalogue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                    Text(
                                        "${products.size} listed products",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    selectedProductToEdit = null
                                    showAddEditDialog = true
                                }) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Text("Add")
                                }
                            }
                            if (products.isEmpty()) {
                                Text("No products yet. Tap + to add your first product.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(products, key = { it.id }) { product ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (product.imageUrl.isNotEmpty()) {
                                    AsyncImage(
                                        model = product.imageUrl,
                                        contentDescription = product.name,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(160.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = product.name, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
                                    Text(
                                        text = "₱${formatPhpPesos(product.price)}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (product.description.isNotBlank()) {
                                    Text(text = product.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = product.category,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }
                                    Text(text = if (product.stockQuantity <= 0) "Out of stock" else "${product.stockQuantity} units in stock", style = MaterialTheme.typography.labelMedium, color = if (product.stockQuantity <= 5) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                                }

                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 4.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = product.isAvailable,
                                            enabled = product.stockQuantity > 0,
                                            onCheckedChange = { screenModel.toggleProductAvailability(product) }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(when { product.stockQuantity <= 0 -> "Out of stock"; product.isAvailable -> "Available"; else -> "Hidden" }, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    }

                                    Row {
                                        IconButton(onClick = {
                                            selectedProductToEdit = product
                                            showAddEditDialog = true
                                        }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { screenModel.deleteProduct(product.id) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showAddEditDialog) {
                AddEditProductDialog(
                    product = selectedProductToEdit,
                    uploadState = imageUpload,
                    onUploadImage = { image, token -> screenModel.uploadProductImage(image, token) },
                    onUploadHandled = { screenModel.resetImageUpload() },
                    onDismiss = { showAddEditDialog = false },
                    onConfirm = { name, desc, price, cat, img, stock ->
                        screenModel.addOrUpdateProduct(
                            productId = selectedProductToEdit?.id,
                            name = name,
                            description = desc,
                            price = price,
                            category = cat,
                            imageUrl = img,
                            stockQuantity = stock,
                            onSuccess = { showAddEditDialog = false }
                        )
                    }
                )
            }
        }
    }


    @Composable
    private fun SummaryTile(label: String, count: Int, modifier: Modifier = Modifier) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(count.toString(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun SalesTile(label: String, amount: Double, modifier: Modifier = Modifier) {
        Card(
            modifier = modifier,
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("₱${formatPhpPesos(amount)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text("Delivered orders", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
            }
        }
    }

    @Composable
    private fun AddEditProductDialog(
        product: Product?,
        uploadState: ImageUploadState,
        onUploadImage: (PickedImage, DriveTokenProvider) -> Unit,
        onUploadHandled: () -> Unit,
        onDismiss: () -> Unit,
        onConfirm: (String, String, Double, String, String, Int) -> Unit
    ) {
        var name by remember { mutableStateOf(product?.name ?: "") }
        var description by remember { mutableStateOf(product?.description ?: "") }
        var price by remember { mutableStateOf(product?.price?.toString() ?: "") }
        var category by remember { mutableStateOf(product?.category ?: "General") }
        var imageUrl by remember { mutableStateOf(product?.imageUrl ?: "") }
        var stockQuantity by remember { mutableStateOf(product?.stockQuantity?.toString() ?: "10") }

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

        AlertDialog(
            onDismissRequest = onDismiss,
            shape = RoundedCornerShape(20.dp),
            title = { Text(if (product == null) "Add New Product" else "Edit Product", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ImagePicker(
                        imageUrl = imageUrl,
                        uploadState = uploadState,
                        onImagePicked = onUploadImage,
                        height = 140.dp,
                        emptyLabel = "Add a product photo",
                        contentDescription = "Product photo"
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Product Name") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("Price (₱)") },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = stockQuantity,
                        onValueChange = { stockQuantity = it },
                        label = { Text("Stock Quantity") },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onConfirm(
                            name,
                            description,
                            price.toDoubleOrNull() ?: 0.0,
                            category,
                            imageUrl,
                            stockQuantity.toIntOrNull() ?: 0
                        )
                    },
                    enabled = name.isNotBlank() && price.toDoubleOrNull()?.let { it >= 0.0 } == true && stockQuantity.toIntOrNull()?.let { it >= 0 } == true && !isUploading,
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(if (isUploading) "Uploading photo…" else "Save Product", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        )
    }
}