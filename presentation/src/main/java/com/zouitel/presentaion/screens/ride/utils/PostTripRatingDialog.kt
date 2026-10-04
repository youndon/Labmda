package com.zouitel.presentaion.screens.ride.utils

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostTripRatingDialog(
    driverName: String,
    onDismissRequest: () -> Unit,
    onSubmit: (rating: Int, tipAmount: Double, feedback: String) -> Unit
) {
    var rating by remember { mutableIntStateOf(5) }
    var selectedTipIndex by remember { mutableIntStateOf(0) } // 0 = No Tip
    var customTipText by remember { mutableStateOf("") }
    var feedbackText by remember { mutableStateOf("") }

    val tipOptions = remember { listOf(0.0, 1.0, 2.0, 5.0) }
    val isCustomTip = selectedTipIndex == tipOptions.size

    val calculatedTip: Double = if (isCustomTip) {
        customTipText.toDoubleOrNull() ?: 0.0
    } else {
        tipOptions[selectedTipIndex]
    }

    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header Title
                Text(
                    text = "Rate your ride with $driverName",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Interactive Star Rating (1 to 5 Stars)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    (1..5).forEach { starIndex ->
                        val isSelected = starIndex <= rating
                        val iconColor by animateColorAsState(
                            targetValue = if (isSelected) Color(0xFFFFB300) else Color.LightGray,
                            label = "star_color"
                        )

                        Icon(
                            imageVector = if (isSelected) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = "Star $starIndex",
                            tint = iconColor,
                            modifier = Modifier
                                .size(36.dp)
                                .clickable { rating = starIndex }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(16.dp))

                // Tip Header
                Text(
                    text = "Add a tip for $driverName",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Tip Preset Chips (No Tip, $1, $2, $5, Custom)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tipOptions.forEachIndexed { index, amount ->
                        val isSelected = selectedTipIndex == index
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedTipIndex = index },
                            label = {
                                Text(
                                    text = if (amount == 0.0) "No Tip" else "$${amount.toInt()}",
                                    fontSize = 12.sp
                                )
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    // Custom Tip Chip
                    FilterChip(
                        selected = isCustomTip,
                        onClick = { selectedTipIndex = tipOptions.size },
                        label = { Text("Custom", fontSize = 12.sp) },
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                // Custom Tip Input Field
                if (isCustomTip) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = customTipText,
                        onValueChange = { input ->
                            customTipText = input.filter { it.isDigit() || it == '.' }
                        },
                        label = { Text("Enter Tip Amount ($)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Optional Written Feedback Text Field
                OutlinedTextField(
                    value = feedbackText,
                    onValueChange = { feedbackText = it },
                    placeholder = { Text("Write feedback (optional)...", fontSize = 13.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Skip", color = Color.Gray)
                    }

                    Button(
                        onClick = {
                            onSubmit(rating, calculatedTip, feedbackText)
                        },
                        modifier = Modifier
                            .weight(2f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2196F3))
                    ) {
                        Text(
                            text = if (calculatedTip > 0) "Submit ($${"%.2f".format(calculatedTip)})" else "Submit",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}