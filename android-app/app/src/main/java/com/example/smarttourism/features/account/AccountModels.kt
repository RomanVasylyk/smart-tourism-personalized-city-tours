package com.example.smarttourism.features.account

import retrofit2.HttpException
import java.io.IOException

data class AccountUser(
    val id: Int,
    val email: String,
    val displayName: String?
)

internal enum class AccountMode {
    SIGN_IN,
    REGISTER
}

internal enum class AccountError {
    INVALID_EMAIL,
    MISSING_PASSWORD,
    SHORT_PASSWORD,
    INVALID_CREDENTIALS,
    EMAIL_TAKEN,
    INVALID_INPUT,
    ACCOUNT_DISABLED,
    TOO_MANY_ATTEMPTS,
    SESSION_EXPIRED,
    NETWORK,
    UNKNOWN
}

internal sealed interface AccountResult {
    data class Success(val linkedSessions: Int = 0) : AccountResult

    data class Failure(val error: AccountError) : AccountResult
}

internal data class AccountUiState(
    val user: AccountUser? = null,
    val mode: AccountMode = AccountMode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val isBusy: Boolean = false,
    val error: AccountError? = null,
    val linkedSessions: Int? = null
)

internal const val MinPasswordLength = 8

private val EmailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

internal fun validateAccountForm(mode: AccountMode, email: String, password: String): AccountError? =
    when {
        !EmailPattern.matches(email.trim()) -> AccountError.INVALID_EMAIL
        password.isEmpty() -> AccountError.MISSING_PASSWORD
        mode == AccountMode.REGISTER && password.length < MinPasswordLength -> AccountError.SHORT_PASSWORD
        else -> null
    }

internal fun accountErrorOf(error: Throwable): AccountError =
    when (error) {
        is HttpException -> when (error.code()) {
            401 -> AccountError.INVALID_CREDENTIALS
            403 -> AccountError.ACCOUNT_DISABLED
            409 -> AccountError.EMAIL_TAKEN
            422 -> AccountError.INVALID_INPUT
            429 -> AccountError.TOO_MANY_ATTEMPTS
            else -> AccountError.UNKNOWN
        }
        is IOException -> AccountError.NETWORK
        else -> AccountError.UNKNOWN
    }
