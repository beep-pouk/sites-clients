package com.securechat.app.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.securechat.app.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class OnboardingUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val completed: Boolean = false,
)

class OnboardingViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    fun createIdentityAndRegister(displayName: String) {
        val trimmed = displayName.trim()
        if (trimmed.isEmpty()) {
            _state.value = _state.value.copy(error = "Please enter a display name")
            return
        }
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            runCatching {
                container.keyStorage.saveDisplayName(trimmed)
                container.keyRepository.ensureIdentityAndRegister()
            }.onSuccess {
                _state.value = _state.value.copy(isLoading = false, completed = true)
            }.onFailure { error ->
                _state.value = _state.value.copy(isLoading = false, error = error.message ?: "Registration failed")
            }
        }
    }
}
