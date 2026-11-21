package com.example.smarttourism.features.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R

internal data class AccountSheetActions(
    val onModeSelected: (AccountMode) -> Unit,
    val onEmailChange: (String) -> Unit,
    val onPasswordChange: (String) -> Unit,
    val onDisplayNameChange: (String) -> Unit,
    val onSubmit: () -> Unit,
    val onSignOut: () -> Unit,
    val onDeleteAccount: () -> Unit,
    val onDismiss: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountSheet(
    state: AccountUiState,
    actions: AccountSheetActions
) {
    var isDeleteDialogOpen by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = actions.onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.account_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            val user = state.user
            if (user == null) {
                AccountSignInForm(state = state, actions = actions)
            } else {
                AccountSignedInContent(
                    state = state,
                    user = user,
                    onSignOut = actions.onSignOut,
                    onDeleteRequest = { isDeleteDialogOpen = true }
                )
            }
        }
    }

    if (isDeleteDialogOpen) {
        AlertDialog(
            onDismissRequest = { isDeleteDialogOpen = false },
            title = { Text(stringResource(R.string.account_delete_confirm_title)) },
            text = { Text(stringResource(R.string.account_delete_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleteDialogOpen = false
                        actions.onDeleteAccount()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.account_delete_confirm_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { isDeleteDialogOpen = false }) {
                    Text(stringResource(R.string.account_keep))
                }
            }
        )
    }
}

@Composable
private fun AccountSignInForm(
    state: AccountUiState,
    actions: AccountSheetActions
) {
    val isRegistration = state.mode == AccountMode.REGISTER

    Text(
        text = stringResource(R.string.account_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    TabRow(selectedTabIndex = state.mode.ordinal) {
        Tab(
            selected = !isRegistration,
            enabled = !state.isBusy,
            onClick = { actions.onModeSelected(AccountMode.SIGN_IN) },
            text = { Text(stringResource(R.string.account_sign_in)) }
        )
        Tab(
            selected = isRegistration,
            enabled = !state.isBusy,
            onClick = { actions.onModeSelected(AccountMode.REGISTER) },
            text = { Text(stringResource(R.string.account_register)) }
        )
    }
    OutlinedTextField(
        value = state.email,
        onValueChange = actions.onEmailChange,
        label = { Text(stringResource(R.string.account_email)) },
        singleLine = true,
        enabled = !state.isBusy,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth()
    )
    if (isRegistration) {
        OutlinedTextField(
            value = state.displayName,
            onValueChange = actions.onDisplayNameChange,
            label = { Text(stringResource(R.string.account_display_name)) },
            singleLine = true,
            enabled = !state.isBusy,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
    OutlinedTextField(
        value = state.password,
        onValueChange = actions.onPasswordChange,
        label = { Text(stringResource(R.string.account_password)) },
        supportingText = if (isRegistration) {
            { Text(stringResource(R.string.account_password_hint)) }
        } else {
            null
        },
        singleLine = true,
        enabled = !state.isBusy,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { actions.onSubmit() }),
        modifier = Modifier.fillMaxWidth()
    )
    state.error?.let { error -> AccountErrorText(error) }
    Button(
        onClick = actions.onSubmit,
        enabled = !state.isBusy,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(stringResource(if (isRegistration) R.string.account_register else R.string.account_sign_in))
        }
    }
}

@Composable
private fun AccountSignedInContent(
    state: AccountUiState,
    user: AccountUser,
    onSignOut: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(40.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = user.displayName?.takeIf { name -> name.isNotBlank() } ?: user.email,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = stringResource(R.string.account_signed_in_as, user.email),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    Text(
        text = stringResource(R.string.account_signed_in_body),
        style = MaterialTheme.typography.bodyMedium
    )
    state.linkedSessions?.takeIf { count -> count > 0 }?.let { count ->
        Text(
            text = stringResource(R.string.account_linked_sessions, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    state.error?.let { error -> AccountErrorText(error) }
    OutlinedButton(
        onClick = onSignOut,
        enabled = !state.isBusy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.account_sign_out))
    }
    TextButton(
        onClick = onDeleteRequest,
        enabled = !state.isBusy,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = stringResource(R.string.account_delete),
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun AccountErrorText(error: AccountError) {
    Text(
        text = stringResource(accountErrorMessage(error)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
}

internal fun accountErrorMessage(error: AccountError): Int =
    when (error) {
        AccountError.INVALID_EMAIL -> R.string.account_error_invalid_email
        AccountError.MISSING_PASSWORD -> R.string.account_error_missing_password
        AccountError.SHORT_PASSWORD -> R.string.account_error_short_password
        AccountError.INVALID_CREDENTIALS -> R.string.account_error_invalid_credentials
        AccountError.EMAIL_TAKEN -> R.string.account_error_email_taken
        AccountError.INVALID_INPUT -> R.string.account_error_invalid_input
        AccountError.ACCOUNT_DISABLED -> R.string.account_error_disabled
        AccountError.TOO_MANY_ATTEMPTS -> R.string.account_error_too_many_attempts
        AccountError.SESSION_EXPIRED -> R.string.account_error_session_expired
        AccountError.NETWORK -> R.string.account_error_network
        AccountError.UNKNOWN -> R.string.account_error_unknown
    }
