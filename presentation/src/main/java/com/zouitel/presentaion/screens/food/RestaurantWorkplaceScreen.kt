package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import dev.gitlive.firebase.firestore.invoke

class RestaurantWorkplaceScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = koinScreenModel<RestaurantWorkplaceScreenModel>()

        var name by remember { mutableStateOf("") }
        var imageUrl by remember { mutableStateOf("") }
        var deliveryFee by remember { mutableStateOf("") }
        var estimatedTime by remember { mutableStateOf("") }
        var manager by remember { mutableStateOf("") }
        val managers = remember { mutableStateListOf<String>() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Add Restaurant Unit") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Restaurant Name") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = imageUrl,
                    onValueChange = { imageUrl = it },
                    label = { Text("Image URL") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = manager,
                    onValueChange = { manager = it },
                    label = { Text("Manager Phone Number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    keyboardActions =  KeyboardActions(onDone = { managers.add(manager); manager = "" }),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = deliveryFee,
                    onValueChange = { deliveryFee = it },
                    label = { Text("Courier Fee ($)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = estimatedTime,
                    onValueChange = { estimatedTime = it },
                    label = { Text("Estimated Time (Minutes)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        screenModel.saveRestaurant(
                            name = name,
                            imageUrl = imageUrl,
                            deliveryFee = deliveryFee.toDoubleOrNull() ?: 0.0,
                            managers = managers.toList(),
                            estimatedTimeMinutes = estimatedTime.toIntOrNull() ?: 20,
                            onSuccess = { navigator?.pop() }
                        )
                    },
                    enabled = name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Restaurant")
                }
            }
        }
    }
}