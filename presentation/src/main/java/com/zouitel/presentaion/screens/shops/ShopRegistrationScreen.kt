package com.zouitel.presentaion.screens.shops

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator

data class ShopRegistrationScreen(val existingShop: Shop? = null) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { ShopWorkplaceScreenModel() }

        var name by remember { mutableStateOf(existingShop?.name ?: "") }
        var description by remember { mutableStateOf(existingShop?.description ?: "") }
        var category by remember { mutableStateOf(existingShop?.category ?: "Grocery & Supermarket") }
        var phone by remember { mutableStateOf(existingShop?.phone ?: "") }
        var address by remember { mutableStateOf(existingShop?.address ?: "") }
        var imageUrl by remember { mutableStateOf(existingShop?.imageUrl ?: "") }
        var isOnline by remember { mutableStateOf(existingShop?.isOnline ?: false) }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (existingShop == null) "Register Store Business" else "Edit Store Details") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { paddingValues ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = if (existingShop == null) "Submit your store details for admin verification." else "Update your store information.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Store Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Store Description") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = category,
                        onValueChange = { category = it },
                        label = { Text("Store Category") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("Business Phone Number") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Physical Store Address") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    OutlinedTextField(
                        value = imageUrl,
                        onValueChange = { imageUrl = it },
                        label = { Text("Store Image / Logo URL (Optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                item {
                    Button(
                        onClick = {
                            if (existingShop != null) {
                                screenModel.updateShopApplication(
                                    shopId = existingShop.id,
                                    name = name,
                                    description = description,
                                    category = category,
                                    phone = phone,
                                    address = address,
                                    imageUrl = imageUrl
                                )
                            } else {
                                screenModel.submitShopApplication(
                                    name = name,
                                    description = description,
                                    category = category,
                                    phone = phone,
                                    address = address,
                                    imageUrl = imageUrl,
                                    isOnline = isOnline
                                )
                            }
                            navigator?.pop()
                        },
                        enabled = name.isNotBlank() && phone.isNotBlank() && address.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (existingShop == null) "Submit Application" else "Save Changes")
                    }
                }
            }
        }
    }
}