package com.zouitel.presentaion.admin

import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import com.zouitel.presentaion.screens.payment.PaymentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CommissionUiState(
    val settings: CommissionSettings? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

class AdminCommissionScreenModel(private val repository: PaymentRepository) : ScreenModel {
    private val _state = MutableStateFlow(CommissionUiState())
    val state: StateFlow<CommissionUiState> = _state.asStateFlow()

    fun load() {
        screenModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            runCatching { repository.getCommissionSettings() }
                .onSuccess { _state.value = _state.value.copy(settings = it, isLoading = false) }
                .onFailure { _state.value = _state.value.copy(isLoading = false, error = it.message ?: "Unable to load commission settings.") }
        }
    }

    fun save(settings: CommissionSettings) {
        screenModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, error = null, message = null)
            runCatching { repository.saveCommissionSettings(settings) }
                .onSuccess {
                    _state.value = _state.value.copy(settings = settings, isSaving = false, message = "Commission update submitted for server validation.")
                }
                .onFailure { _state.value = _state.value.copy(isSaving = false, error = it.message ?: "Unable to save commission settings.") }
        }
    }
}