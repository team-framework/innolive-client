package com.framework.innolive.feature.login.oauth.google

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.ui.text.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoogleSignInControllerTest {
    @Test
    fun retainedOwnerPreventsDuplicateGoogleRequestsWhileLoginScreenRecreates() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Unit>()
        var requests = 0
        val controller = GoogleSignInController(scope) {
            requests += 1
            started.complete(Unit)
            completion.await()
        }

        try {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            controller.start(context)
            started.await()
            controller.state.first { it is GoogleSignInState.InProgress }

            // A configuration-recreated LoginScreen observes this same owner and must not relaunch.
            controller.start(context)
            assertEquals(1, requests)

            completion.complete(Unit)
            controller.state.first { it is GoogleSignInState.Succeeded }
            assertEquals(1, requests)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clientFailureIsLocalizedResourceState() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = GoogleSignInController(scope) { error("credential failure") }

        try {
            controller.start(InstrumentationRegistry.getInstrumentation().targetContext)
            assertEquals(
                GoogleSignInState.Failed(UiText.Resource(R.string.google_login_failed)),
                controller.state.first { it is GoogleSignInState.Failed },
            )
        } finally {
            scope.cancel()
        }
    }
}
