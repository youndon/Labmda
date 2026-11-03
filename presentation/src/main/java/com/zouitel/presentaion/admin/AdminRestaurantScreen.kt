package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.zouitel.presentaion.common.Status
import com.zouitel.presentaion.model.UserProfile

class AdminRestaurantScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val screenModel = rememberScreenModel { AdminRestaurantModel() }
        val applications by screenModel.applications.collectAsState()
        var query by remember { mutableStateOf("") }
        val filtered = applications.filter {
            it.fullName.contains(query, ignoreCase = true) || it.phoneNumber.orEmpty().contains(query, ignoreCase = true)
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Restaurant applications", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    AdminApplicationHeader(
                        count = applications.size,
                        description = "Review restaurant operator applications before granting restaurant access.",
                        query = query,
                        onQueryChange = { query = it },
                        searchPlaceholder = "Search manager name or phone"
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        AdminApplicationEmptyState(
                            title = if (applications.isEmpty()) "No pending restaurant applications" else "No matching restaurants",
                            description = if (applications.isEmpty()) "New restaurant applications will appear here." else "Try another manager name or phone number."
                        )
                    }
                } else {
                    items(filtered, key = { it.uid }) { profile ->
                        RestaurantApplicationCard(
                            profile = profile,
                            onApprove = { screenModel.updateRestaurantStatus(profile.uid, Status.APPROVED) },
                            onReject = { screenModel.updateRestaurantStatus(profile.uid, Status.REJECTED) }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun RestaurantApplicationCard(profile: UserProfile, onApprove: () -> Unit, onReject: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(profile.fullName.ifBlank { "Restaurant manager" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(profile.phoneNumber?.takeIf { it.isNotBlank() } ?: "Phone not provided", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    AdminPendingLabel()
                }
                Text("Restaurant service application", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Button(onClick = onApprove, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Approve")
                    }
                    OutlinedButton(
                        onClick = onReject,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("Reject")
                    }
                }
            }
        }
    }
}