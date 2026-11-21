package com.example.smarttourism.features.account

import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.remote.dto.AccountUserDto
import com.example.smarttourism.data.remote.dto.AuthResponseDto
import com.example.smarttourism.data.remote.dto.LoginRequest
import com.example.smarttourism.data.remote.dto.RegisterRequest
import kotlinx.coroutines.flow.StateFlow
import retrofit2.HttpException
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

internal class AccountRepository @Inject constructor(
    private val api: PoiApi,
    private val store: AuthSessionStore
) {
    val user: StateFlow<AccountUser?> = store.user

    suspend fun signIn(email: String, password: String): AccountResult =
        authenticate {
            api.login(LoginRequest(email = email.trim(), password = password))
        }

    suspend fun register(email: String, password: String, displayName: String): AccountResult =
        authenticate {
            api.register(
                RegisterRequest(
                    email = email.trim(),
                    password = password,
                    display_name = displayName.trim().ifEmpty { null }
                )
            )
        }

    suspend fun refreshUser() {
        if (!store.isSignedIn()) {
            return
        }
        try {
            store.updateUser(api.getCurrentUser().toAccountUser())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is HttpException && error.code() == 401) {
                store.clear()
            }
        }
    }

    suspend fun signOut() {
        runCatching { api.logout() }
        store.clear()
    }

    suspend fun deleteAccount(): AccountResult =
        try {
            api.deleteCurrentUser()
            store.clear()
            AccountResult.Success()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is HttpException && error.code() == 401) {
                store.clear()
                AccountResult.Failure(AccountError.SESSION_EXPIRED)
            } else {
                AccountResult.Failure(accountErrorOf(error))
            }
        }

    private suspend fun authenticate(request: suspend () -> AuthResponseDto): AccountResult =
        try {
            val response = request()
            store.save(response.token, response.user.toAccountUser())
            AccountResult.Success(linkedSessions = linkThisDevice())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            AccountResult.Failure(accountErrorOf(error))
        }

    private suspend fun linkThisDevice(): Int =
        try {
            api.linkDevice().linked_sessions
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            0
        }
}

internal fun AccountUserDto.toAccountUser(): AccountUser =
    AccountUser(
        id = id,
        email = email,
        displayName = display_name
    )
