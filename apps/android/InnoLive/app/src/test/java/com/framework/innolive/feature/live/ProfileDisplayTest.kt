package com.framework.innolive.feature.live

import com.framework.innolive.R
import com.framework.innolive.feature.login.oauth.google.SignInMethod
import com.framework.innolive.feature.login.oauth.google.storedSignInMethod
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileDisplayTest {
    @Test
    fun fallbackNameFollowsSignInMethodWhenEmailExists() {
        val email = "member@example.com"
        assertEquals(R.string.google_user, profileFallbackName(email, SignInMethod.GOOGLE))
        assertEquals(R.string.email_user, profileFallbackName(email, SignInMethod.EMAIL))
        assertEquals(R.string.profile_default_name, profileFallbackName(email, SignInMethod.UNKNOWN))
    }

    @Test
    fun fallbackNameIsDefaultWithoutEmail() {
        for (method in SignInMethod.entries) {
            assertEquals(method.name, R.string.profile_default_name, profileFallbackName("", method))
        }
    }

    @Test
    fun legacySessionInfersSignInMethodFromProfileName() {
        assertEquals(SignInMethod.EMAIL, storedSignInMethod("EMAIL", "InnoLive User"))
        assertEquals(SignInMethod.GOOGLE, storedSignInMethod("GOOGLE", ""))
        assertEquals(SignInMethod.GOOGLE, storedSignInMethod("", "InnoLive User"))
        assertEquals(SignInMethod.EMAIL, storedSignInMethod("", ""))
    }
}
