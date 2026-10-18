package com.zouitel.presentaion.screens.food

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.ImagePicker

data class MealWorkplaceScreen(val restaurantId: String, val meal: Meal? = null) : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = koinScreenModel<MealWorkplaceScreenModel>()
        val imageUpload by screenModel.imageUpload.collectAsState()
        val isUploading = imageUpload is ImageUploadState.Uploading

        var name by remember { mutableStateOf(meal?.name ?: "") }
        var description by remember { mutableStateOf(meal?.description ?: "") }
        var basePrice by remember { mutableStateOf(if (meal != null && meal.basePrice > 0.0) meal.basePrice.toString() else "") }
        var imageUrl by remember { mutableStateOf(meal?.imageUrls?.firstOrNull() ?: "") }
        var selectedCategory by remember { mutableStateOf(meal?.category ?: MealCategory.BREAKFAST) }
        var expandedCategoryDropdown by remember { mutableStateOf(false) }

        LaunchedEffect(imageUpload) {
            val state = imageUpload
            if (state is ImageUploadState.Success) {
                imageUrl = state.url
                screenModel.resetImageUpload()
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text(if (meal == null) "Add Meal Unit" else "Edit Meal Unit", fontWeight = FontWeight.Bold) },
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
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Meal Name") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text("Description") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = basePrice,
                        onValueChange = { basePrice = it },
                        label = { Text("Price ($)") },
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    ImagePicker(
                        imageUrl = imageUrl,
                        uploadState = imageUpload,
                        onImagePicked = { image, token ->
                            screenModel.uploadMealImage(image, token)
                        },
                        emptyLabel = "Add a meal photo",
                        contentDescription = "Meal photo"
                    )

                    ExposedDropdownMenuBox(
                        expanded = expandedCategoryDropdown,
                        onExpandedChange = { expandedCategoryDropdown = !expandedCategoryDropdown }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category") },
                            shape = RoundedCornerShape(12.dp),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCategoryDropdown) },
                            modifier = Modifier
                                .menuAnchor(
                                    type = ExposedDropdownMenuAnchorType.PrimaryEditable,
                                    enabled = true
                                )
                                .fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = expandedCategoryDropdown,
                            onDismissRequest = { expandedCategoryDropdown = false }
                        ) {
                            MealCategory.entries.filter { it != MealCategory.UNKNOWN }.forEach { category ->
                                DropdownMenuItem(
                                    text = { Text(category.name) },
                                    onClick = {
                                        selectedCategory = category
                                        expandedCategoryDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (meal != null) {
                        OutlinedButton(
                            onClick = {
                                screenModel.deleteMeal(
                                    restaurantId = restaurantId,
                                    mealId = meal.id,
                                    onSuccess = { navigator?.pop() }
                                )
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Delete Meal")
                        }
                    }

                    Button(
                        onClick = {
                            screenModel.saveMeal(
                                restaurantId = restaurantId,
                                mealId = meal?.id ?: "",
                                name = name,
                                description = description,
                                basePrice = basePrice.toDoubleOrNull() ?: 0.0,
                                category = selectedCategory,
                                imageUrl = imageUrl,
                                onSuccess = { navigator?.pop() }
                            )
                        },
                        enabled = name.isNotBlank() && basePrice.isNotBlank() && !isUploading,
                        modifier = Modifier.weight(1f).height(50.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            when {
                                isUploading -> "Uploading photo…"
                                meal == null -> "Save Meal"
                                else -> "Update Meal"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}