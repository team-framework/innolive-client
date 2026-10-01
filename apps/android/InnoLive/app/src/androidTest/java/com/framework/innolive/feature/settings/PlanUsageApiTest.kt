package com.framework.innolive.feature.settings

import com.framework.innolive.feature.youtube.YouTubeApi
import com.framework.innolive.feature.youtube.YouTubeApiException
import com.framework.innolive.feature.youtube.YouTubeAccountCoordinator
import com.framework.innolive.feature.youtube.retryYouTubeUnauthorized
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class PlanUsageApiTest {
    private val plan = """{"plan":"spark","allowed_modes":["720p_single"],"monthly_broadcast_seconds":18000,"max_per_broadcast_seconds":7200}"""
    private val usage = """{"plan":"spark","used_seconds":3600,"remaining_seconds":14400,"available_by_mode":[{"mode":"720p_single","allowed":true,"seconds":14400,"multiplier":1}]}"""

    @Test fun authenticatedPlanAndUsageUseExistingRefreshOnceAndNoOwnerToken() = runBlocking {
        val requests = mutableListOf<Request>()
        var refreshes = 0
        fixture { request ->
            requests += request
            if (request.header("Authorization") == "Bearer expired") 401 to "{}"
            else 200 to if (request.url.encodedPath.endsWith("plan")) plan else usage
        }.use { api ->
            val snapshot = coordinator(api).loadPlanUsage("expired", { refreshes++; "fresh" })
            assertEquals("spark", snapshot.plan)
            assertEquals(14400L, snapshot.remainingSeconds)
            assertEquals(1, refreshes)
            assertEquals(listOf("/users/me/plan", "/users/me/plan", "/users/me/usage"), requests.map { it.url.encodedPath })
            assertTrue(requests.all { it.method == "GET" && it.header("X-Session-Owner-Token") == null })
            assertTrue(requests.drop(1).all { it.header("Authorization") == "Bearer fresh" })
        }
    }

    @Test fun unauthorizedUsageRetriesTheCompletePairAndSecond401Stops() = runBlocking {
        var calls = 0
        var refreshes = 0
        fixture { request ->
            calls++
            if (request.url.encodedPath.endsWith("plan")) 200 to plan else 401 to "{}"
        }.use { api ->
            try {
                retryYouTubeUnauthorized("expired", { refreshes++; "fresh" }) { api.planUsage(it) }
                fail("Expected 401")
            } catch (exception: YouTubeApiException) {
                assertEquals(401, exception.statusCode)
                assertEquals(1, refreshes)
                assertEquals(4, calls)
            }
        }
    }

    @Test fun partialOrInconsistentPairIsNotPublished() = runBlocking {
        for (reply in listOf(503 to "{}", 200 to usage.replace("spark", "beam"), 200 to usage.replace("\"remaining_seconds\":14400,", ""))) {
            fixture { request -> if (request.url.encodedPath.endsWith("plan")) 200 to plan else reply }.use { api ->
                try { api.planUsage("fixture-token"); fail("Expected rejection") } catch (_: Exception) { }
            }
        }
    }

    @Test fun failedAuthenticationRefreshReturns401ForLoginRecovery() = runBlocking {
        fixture { 401 to "{}" }.use { api ->
            try {
                coordinator(api).loadPlanUsage("expired", { throw java.io.IOException("fixture") })
                fail("Expected authentication failure")
            } catch (exception: YouTubeApiException) {
                assertEquals(401, exception.statusCode)
            }
        }
    }

    private fun coordinator(api: YouTubeApi) = YouTubeAccountCoordinator(null).also {
        YouTubeAccountCoordinator::class.java.getDeclaredField("api").apply { isAccessible = true; set(it, api) }
    }

    private fun fixture(reply: (Request) -> Pair<Int, String>): YouTubeApi {
        val api = YouTubeApi("https://example.test")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val (status, body) = reply(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
                .body(body.toResponseBody()).build()
        }.build()
        YouTubeApi::class.java.getDeclaredField("httpClient").apply { isAccessible = true; set(api, client) }
        return api
    }
}
