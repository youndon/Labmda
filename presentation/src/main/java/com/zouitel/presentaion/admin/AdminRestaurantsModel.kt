package com.zouitel.presentaion.admin

    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.foundation.lazy.items
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material.icons.filled.Delete
    import androidx.compose.material.icons.filled.Restaurant
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.unit.dp
    import cafe.adriel.voyager.core.model.ScreenModel
    import cafe.adriel.voyager.core.model.rememberScreenModel
    import cafe.adriel.voyager.core.model.screenModelScope
    import cafe.adriel.voyager.core.screen.Screen
    import cafe.adriel.voyager.navigator.LocalNavigator
    import com.zouitel.presentaion.screens.food.FoodRepository
    import com.zouitel.presentaion.screens.food.Restaurant
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.launch

// ScreenModel for Admin Restaurant Management
class AdminRestaurantsModel(
    private val repository: FoodRepository = FoodRepository()
) : ScreenModel {
    private val _restaurants = MutableStateFlow<List<Restaurant>>(emptyList())
    val restaurants: StateFlow<List<Restaurant>> = _restaurants.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadRestaurants()
    }

    private fun loadRestaurants() {
        screenModelScope.launch(Dispatchers.IO) {
            repository.getRestaurants().collect { list ->
                _restaurants.value = list
                _isLoading.value = false
            }
        }
    }

    fun deleteRestaurant(restaurantId: String) {
        screenModelScope.launch(Dispatchers.IO) {
            // Add repository deletion trigger if applicable or handle removal via DB reference
        }
    }
}

