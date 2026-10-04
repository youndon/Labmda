package com.zouitel.presentaion.screens.ride.passenger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.koinScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.zouitel.presentaion.model.Trip
import com.zouitel.presentaion.screens.ride.driver.DriverScreenModel
import kotlinx.coroutines.launch

class TripRatingScreen(
    private val trip: Trip
) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val coroutineScope = rememberCoroutineScope()
        val screenModel = koinScreenModel<DriverScreenModel>()

        var rating by remember { mutableIntStateOf(5) }
        var selectedTip by remember { mutableDoubleStateOf(2.0) }
        var feedbackText by remember { mutableStateOf("") }
        var isSubmitting by remember { mutableStateOf(false) }

        val tipOptions = listOf(0.0, 1.0, 2.0, 5.0)

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("How was your trip?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("Your feedback improves driver quality", fontSize = 14.sp, color = Color.Gray)

                    Spacer(modifier = Modifier.height(32.dp))

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(80.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            modifier = Modifier.padding(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = trip.driverId ?: "Driver",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(text = "Final Fare: $${trip.fareAmount}", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

                    Spacer(modifier = Modifier.height(24.dp))

                    // Star Rating Selection
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        (1..5).forEach { starIndex ->
                            IconButton(onClick = { rating = starIndex }) {
                                Icon(
                                    imageVector = if (starIndex <= rating) Icons.Default.Star else Icons.Outlined.Star,
                                    contentDescription = "Star $starIndex",
                                    tint = if (starIndex <= rating) Color(0xFFFFC107) else Color.Gray,
                                    modifier = Modifier.size(36.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // Tip Selection
                    Text("Add a tip for your driver", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        tipOptions.forEach { tip ->
                            val isSelected = selectedTip == tip
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedTip = tip },
                                label = {
                                    Text(
                                        text = if (tip == 0.0) "No Tip" else "+$$tip",
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedTextField(
                        value = feedbackText,
                        onValueChange = { feedbackText = it },
                        label = { Text("Leave additional comment (optional)") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Button(
                    onClick = {
                        isSubmitting = true
                        coroutineScope.launch {
                            screenModel.submitTripRating(
                                tripId = trip.id,
                                driverId = trip.driverId ?: "",
                                rating = rating,
                                tipAmount = selectedTip,
                                feedback = feedbackText
                            )
                            isSubmitting = false
                            navigator.pop() // Return to Home
                        }
                    },
                    enabled = !isSubmitting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                    } else {
                        Text("SUBMIT & CLOSE", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}