package com.example.smarttourism.features.account

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
internal class AccountViewModel @Inject constructor(
    private val repository: AccountRepository
) : ViewModel() {
    private val state = MutableStateFlow(AccountUiState(user = repository.user.value))
    val uiState: StateFlow<AccountUiState> = state.asStateFlow()

    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val accountChanges: SharedFlow<Unit> = changes.asSharedFlow()

    init {
        viewModelScope.launch {
            repository.user.collect { user ->
                state.update { current -> current.copy(user = user) }
            }
        }
    }

    fun onOpened() {
        state.update { current -> current.copy(error = null) }
        viewModelScope.launch { repository.refreshUser() }
    }

    fun selectMode(mode: AccountMode) {
        state.update { current -> current.copy(mode = mode, error = null) }
    }

    fun updateEmail(value: String) {
        state.update { current -> current.copy(email = value, error = null) }
    }

    fun updatePassword(value: String) {
        state.update { current -> current.copy(password = value, error = null) }
    }

    fun updateDisplayName(value: String) {
        state.update { current -> current.copy(displayName = value, error = null) }
    }

    fun submit() {
        val current = state.value
        if (current.isBusy) {
            return
        }
        val validationError = validateAccountForm(current.mode, current.email, current.password)
        if (validationError != null) {
            state.update { it.copy(error = validationError) }
            return
        }
        state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = when (current.mode) {
                AccountMode.SIGN_IN -> repository.signIn(current.email, current.password)
                AccountMode.REGISTER -> repository.register(current.email, current.password, current.displayName)
            }
            when (result) {
                is AccountResult.Success -> {
                    state.update {
                        it.copy(
                            isBusy = false,
                            password = "",
                            displayName = "",
                            linkedSessions = result.linkedSessions
                        )
                    }
                    changes.tryEmit(Unit)
                }
                is AccountResult.Failure -> state.update { it.copy(isBusy = false, error = result.error) }
            }
        }
    }

    fun signOut() {
        if (state.value.isBusy) {
            return
        }
        state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            repository.signOut()
            state.update { it.copy(isBusy = false, linkedSessions = null, mode = AccountMode.SIGN_IN) }
            changes.tryEmit(Unit)
        }
    }

    fun deleteAccount() {
        if (state.value.isBusy) {
            return
        }
        state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (val result = repository.deleteAccount()) {
                is AccountResult.Success -> {
                    state.update { it.copy(isBusy = false, linkedSessions = null, email = "", mode = AccountMode.SIGN_IN) }
                    changes.tryEmit(Unit)
                }
                is AccountResult.Failure -> state.update { it.copy(isBusy = false, error = result.error) }
            }
        }
    }
}
