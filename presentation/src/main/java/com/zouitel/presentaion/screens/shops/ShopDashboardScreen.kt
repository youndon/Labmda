package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator

data class ShopDashboardScreen(val shopId: String) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopDashboardScreenModel(shopId) }
        val products by screenModel.products.collectAsState()
        val isLoading by screenModel.isLoading.collectAsState()

        var showAddEditDialog by remember { mutableStateOf(false) }
        var selectedProductToEdit by remember { mutableStateOf<Product?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Store Manager Dashboard") },
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
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add Product")
                }
            }
        ) { paddingValues ->
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (products.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = Alignment.Center) {
                    Text("No products added to your store yet. Tap + to add one.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(products, key = { it.id }) { product ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text = product.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "$${"%.2f".format(product.price)}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(text = product.description, style = MaterialTheme.typography.bodySmall)
                                Text(text = "Category: ${product.category}", style = MaterialTheme.typography.labelMedium)
                                Text(text = "In Stock: ${product.stockQuantity} units", style = MaterialTheme.typography.labelSmall)

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = product.isAvailable,
                                            onCheckedChange = { screenModel.toggleProductAvailability(product) }
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(if (product.isAvailable) "Available" else "Out of Stock")
                                    }

                                    Row {
                                        IconButton(onClick = {
                                            selectedProductToEdit = product
                                            showAddEditDialog = true
                                        }) {
                                            Icon(Icons.Default.Edit, contentDescription = "Edit")
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
    private fun AddEditProductDialog(
        product: Product?,
        onDismiss: () -> Unit,
        onConfirm: (String, String, Double, String, String, Int) -> Unit
    ) {
        var name by remember { mutableStateOf(product?.name ?: "") }
        var description by remember { mutableStateOf(product?.description ?: "") }
        var price by remember { mutableStateOf(product?.price?.toString() ?: "") }
        var category by remember { mutableStateOf(product?.category ?: "General") }
        var imageUrl by remember { mutableStateOf(product?.imageUrl ?: "") }
        var stockQuantity by remember { mutableStateOf(product?.stockQuantity?.toString() ?: "10") }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(if (product == null) "Add New Product" else "Edit Product") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Product Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it },
                        label = { Text("Price ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = category, onValueChange = { category = it }, label = { Text("Category") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        value = stockQuantity,
                        onValueChange = { stockQuantity = it },
                        label = { Text("Stock Quantity") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(value = imageUrl, onValueChange = { imageUrl = it }, label = { Text("Image URL") }, modifier = Modifier.fillMaxWidth())
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
                    enabled = name.isNotBlank() && price.isNotBlank()
                ) {
                    Text("Save Product")
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