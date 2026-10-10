package com.zouitel.presentaion.admin

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator

class PanelScreen : Screen {
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current

        Scaffold(
            topBar = { TopAppBar(title = { Text("Admin Panel") }) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = { navigator?.push(Couriers()) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Default.DirectionsBike, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage Courier Applications")
                }

                Button(
                    onClick = { navigator?.push(AdminShopsScreen()) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Default.Storefront, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage Shop Applications")
                }

                Button(
                    onClick = { navigator?.push(Restaurants()) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Default.Restaurant, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage Restaurants")
                }

                Button(
                    onClick = { navigator?.push(AdminFreightScreen()) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    Icon(Icons.Default.LocalShipping, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage Freight Applications")
                }
            }
        }
    }
}