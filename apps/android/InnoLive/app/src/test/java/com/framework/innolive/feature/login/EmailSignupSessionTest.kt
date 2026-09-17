package com.framework.innolive.feature.login

import com.framework.innolive.feature.login.oauth.google.GoogleSessionStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class EmailSignupSessionTest {
    private val authenticatedSession = GoogleSessionStore.Session(
        accessToken = "access",
        refreshToken = "refresh",
        tokenType = "Bearer",
        expiresIn = 3600,
        refreshExpiresIn = 7200,
        profileEmail = "member@example.com",
    )

    @Test
    fun verificationUsesPendingCredentialsAndPersistsAuthenticatedSession() = runBlocking {
        val events = mutableListOf<String>()
        var saved: GoogleSessionStore.Session? = null
        val signup = EmailSignupSession(
            signUp = { email, password ->
                events += "signup:$email:$password"
                "signup-token"
            },
            verifyEmail = { token, code -> events += "verify:$token:$code" },
            authenticate = { email, password ->
                events += "signin:$email:$password"
                authenticatedSession
            },
            saveSession = { saved = it },
        )

        signup.start(" Member@Example.com ", "password123")
        signup.verify("012345")

        assertEquals(
            listOf(
                "signup:member@example.com:password123",
                "verify:signup-token:012345",
                "signin:member@example.com:password123",
            ),
            events,
        )
        assertEquals(authenticatedSession, saved)
    }

    @Test
    fun resendReusesPendingCredentialsAndVerificationUsesReplacementToken() = runBlocking {
        val signupCalls = mutableListOf<Pair<String, String>>()
        var nextToken = 0
        var verifiedToken: String? = null
        val signup = EmailSignupSession(
            signUp = { email, password ->
                signupCalls += email to password
                "token-${++nextToken}"
            },
            verifyEmail = { token, _ -> verifiedToken = token },
            authenticate = { _, _ -> authenticatedSession },
            saveSession = {},
        )

        signup.start("member@example.com", "password123")
        signup.resend()
        signup.verify("123456")

        assertEquals(
            listOf(
                "member@example.com" to "password123",
                "member@example.com" to "password123",
            ),
            signupCalls,
        )
        assertEquals("token-2", verifiedToken)
    }

    @Test
    fun failedAuthenticationRetriesWithoutReusingVerificationCodeOrResendingSignup() = runBlocking {
        var verifications = 0
        var signups = 0
        var authentications = 0
        var saved = false
        val signup = EmailSignupSession(
            signUp = { _, _ -> signups++; "token" },
            verifyEmail = { _, _ -> verifications++ },
            authenticate = { _, _ ->
                if (++authentications == 1) throw EmailSignInException("잠시 후 다시 시도해 주세요.")
                authenticatedSession
            },
            saveSession = { saved = true },
        )

        signup.start("member@example.com", "password123")
        assertThrows(EmailSignInException::class.java) {
            runBlocking { signup.verify("123456") }
        }
        assertTrue(signup.hasPendingSignup())
        assertTrue(signup.isVerified())
        assertThrows(EmailSignUpException::class.java) {
            runBlocking { signup.resend() }
        }
        signup.verify("")

        assertEquals(1, signups)
        assertEquals(1, verifications)
        assertEquals(2, authentications)
        assertTrue(saved)
        assertFalse(signup.hasPendingSignup())
    }

    @Test
    fun cancellationRemovesCredentialsAndToken() = runBlocking {
        var authenticated = false
        val signup = EmailSignupSession(
            signUp = { _, _ -> "token" },
            verifyEmail = { _, _ -> },
            authenticate = { _, _ -> authenticated = true; authenticatedSession },
            saveSession = {},
        )

        signup.start("member@example.com", "password123")
        signup.cancel()

        assertThrows(EmailSignUpException::class.java) {
            runBlocking { signup.resend() }
        }
        assertThrows(EmailSignUpException::class.java) {
            runBlocking { signup.verify("123456") }
        }
        assertFalse(authenticated)
    }

    @Test
    fun cancelledVerificationCannotSaveLateAuthenticationResult() = runBlocking {
        val authenticationStarted = CompletableDeferred<Unit>()
        val finishAuthentication = CompletableDeferred<Unit>()
        var saved = false
        val signup = EmailSignupSession(
            signUp = { _, _ -> "token" },
            verifyEmail = { _, _ -> },
            authenticate = { _, _ ->
                authenticationStarted.complete(Unit)
                withContext(NonCancellable) { finishAuthentication.await() }
                authenticatedSession
            },
            saveSession = { saved = true },
        )

        signup.start("member@example.com", "password123")
        val verification = launch { signup.verify("123456") }
        authenticationStarted.await()
        signup.cancel()
        verification.cancel()
        finishAuthentication.complete(Unit)
        verification.join()

        assertFalse(saved)
    }
}
