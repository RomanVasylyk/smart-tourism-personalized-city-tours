package com.example.smarttourism.features.account

import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class AccountModelsTest {
    @Test
    fun signInNeedsValidEmailAndSomePassword() {
        assertEquals(AccountError.INVALID_EMAIL, validateAccountForm(AccountMode.SIGN_IN, "turista", "heslo"))
        assertEquals(AccountError.MISSING_PASSWORD, validateAccountForm(AccountMode.SIGN_IN, " turista@example.com ", ""))
        assertNull(validateAccountForm(AccountMode.SIGN_IN, "turista@example.com", "kratke"))
    }

    @Test
    fun registrationNeedsPasswordOfMinimumLength() {
        assertEquals(
            AccountError.SHORT_PASSWORD,
            validateAccountForm(AccountMode.REGISTER, "turista@example.com", "kratke")
        )
        assertNull(validateAccountForm(AccountMode.REGISTER, "turista@example.com", "dlhe-heslo"))
    }

    @Test
    fun serverAndNetworkFailuresMapToAccountErrors() {
        assertEquals(AccountError.INVALID_CREDENTIALS, accountErrorOf(httpError(401)))
        assertEquals(AccountError.ACCOUNT_DISABLED, accountErrorOf(httpError(403)))
        assertEquals(AccountError.EMAIL_TAKEN, accountErrorOf(httpError(409)))
        assertEquals(AccountError.INVALID_INPUT, accountErrorOf(httpError(422)))
        assertEquals(AccountError.TOO_MANY_ATTEMPTS, accountErrorOf(httpError(429)))
        assertEquals(AccountError.UNKNOWN, accountErrorOf(httpError(500)))
        assertEquals(AccountError.NETWORK, accountErrorOf(IOException("offline")))
    }

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody()))
}
