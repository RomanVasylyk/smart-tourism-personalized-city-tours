package com.example.smarttourism.features.account

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PreferencesName, Context.MODE_PRIVATE)
    private val currentUser = MutableStateFlow(readUser())

    val user: StateFlow<AccountUser?> = currentUser.asStateFlow()

    @Synchronized
    fun token(): String = preferences.getString(TokenKey, null).orEmpty()

    fun isSignedIn(): Boolean = token().isNotEmpty()

    @Synchronized
    fun save(token: String, user: AccountUser) {
        preferences
            .edit()
            .putString(TokenKey, token)
            .putUser(user)
            .commit()
        currentUser.value = user
    }

    @Synchronized
    fun updateUser(user: AccountUser) {
        if (token().isEmpty()) {
            return
        }
        preferences
            .edit()
            .putUser(user)
            .commit()
        currentUser.value = user
    }

    @Synchronized
    fun clear() {
        preferences
            .edit()
            .clear()
            .commit()
        currentUser.value = null
    }

    private fun readUser(): AccountUser? {
        if (preferences.getString(TokenKey, null).isNullOrEmpty()) {
            return null
        }
        val email = preferences.getString(EmailKey, null) ?: return null
        return AccountUser(
            id = preferences.getInt(UserIdKey, 0),
            email = email,
            displayName = preferences.getString(DisplayNameKey, null)
        )
    }

    private fun SharedPreferences.Editor.putUser(user: AccountUser): SharedPreferences.Editor =
        putInt(UserIdKey, user.id)
            .putString(EmailKey, user.email)
            .putString(DisplayNameKey, user.displayName)

    private companion object {
        const val PreferencesName = "account_storage"
        const val TokenKey = "token"
        const val UserIdKey = "user_id"
        const val EmailKey = "email"
        const val DisplayNameKey = "display_name"
    }
}
