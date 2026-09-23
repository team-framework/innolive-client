package com.framework.innolive.feature.face

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.framework.innolive.R
import com.framework.innolive.feature.live.CameraLensFacing
import com.framework.innolive.ui.theme.MyApplicationTheme
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceManagementScreenIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val getCount = AtomicInteger()
    private val deleteCount = AtomicInteger()
    private var deleteResponseCode = 204
    private lateinit var client: OkHttpClient
    private lateinit var imageStore: ReferenceFaceImageStore

    @Before
    fun setUp() {
        imageStore = ReferenceFaceImageStore(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        imageStore.save("e2e@example.com", "face-1", jpeg())
        client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                when (chain.request().method) {
                    "GET" -> {
                        if (getCount.incrementAndGet() == 1) {
                            response(
                                chain.request(),
                                200,
                                """
                                    {
                                      "registered": true,
                                      "source": "api",
                                      "count": 1,
                                      "faces": [{"face_id": "face-1", "registered_at": "2026-09-09T00:00:00Z"}]
                                    }
                                """.trimIndent(),
                            )
                        } else {
                            response(
                                chain.request(),
                                502,
                                "{\"error\":{\"code\":\"ai_unavailable\"}}",
                            )
                        }
                    }

                    "DELETE" -> {
                        deleteCount.incrementAndGet()
                        response(
                            chain.request(),
                            deleteResponseCode,
                            if (deleteResponseCode == 204) "" else
                                "{\"error\":{\"code\":\"ai_unavailable\"}}",
                        )
                    }

                    else -> error("Unexpected method: ${chain.request().method}")
                }
            })
            .build()
    }

    @After
    fun tearDown() {
        imageStore.deleteAll("e2e@example.com")
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    @Test
    fun successfulDeleteRemovesLocalFaceBeforeRefresh502() {
        showScreen()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label(R.string.action_delete)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(faceCount(1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.content_description_registered_face)).assertIsDisplayed()

        composeRule.onNodeWithText(label(R.string.action_delete)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label(R.string.no_registered_faces)).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(label(R.string.no_registered_faces)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.content_description_registered_face)).assertDoesNotExist()
        assertEquals(1, deleteCount.get())
        assertEquals(2, getCount.get())
    }

    @Test
    fun delete502PreservesTheCurrentFaceAndPhoto() {
        deleteResponseCode = 502
        showScreen()

        waitForRegisteredFace()
        composeRule.onNodeWithText(label(R.string.action_delete)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                label(R.string.error_face_server_unavailable),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(faceCount(1)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.content_description_registered_face)).assertIsDisplayed()
        assertEquals(1, getCount.get())
        assertEquals(1, deleteCount.get())
    }

    @Test
    fun deleteAll204HidesLocalFacesWhenRefreshReturns502() {
        showScreen()

        waitForRegisteredFace()
        composeRule.onNodeWithText(label(R.string.action_delete_all_faces)).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label(R.string.no_registered_faces)).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithText(label(R.string.no_registered_faces)).assertIsDisplayed()
        composeRule.onNodeWithContentDescription(label(R.string.content_description_registered_face)).assertDoesNotExist()
        assertEquals(1, deleteCount.get())
        assertEquals(2, getCount.get())
    }

    @Test
    fun missingLocalPhotoDoesNotExposeFaceId() {
        imageStore.deleteAll("e2e@example.com")
        showScreen()

        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label(R.string.no_face_photo)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(label(R.string.no_face_photo)).assertIsDisplayed()
        composeRule.onNodeWithText("face-1").assertDoesNotExist()
    }

    private fun showScreen() {
        composeRule.setContent {
            MyApplicationTheme(dynamicColor = false) {
                FaceManagementScreen(
                    cameraLensFacing = CameraLensFacing.FRONT,
                    onGetAccessToken = { "test-access-token" },
                    onRefreshAccessToken = { error("refresh must not run") },
                    onBack = {},
                    profileEmail = "e2e@example.com",
                    repositoryFactory = { context ->
                        ReferenceFaceRepository(context) {
                            ReferenceFaceApi("https://example.com", client)
                        }
                    },
                )
            }
        }
    }

    private fun waitForRegisteredFace() {
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(label(R.string.action_delete)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun label(id: Int): String = composeRule.activity.getString(id)

    private fun faceCount(count: Int): String =
        composeRule.activity.resources.getQuantityString(R.plurals.registered_face_count, count, count)

    private fun response(request: Request, statusCode: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun jpeg(): ByteArray = ByteArrayOutputStream().use { output ->
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xFF336699.toInt())
            compress(Bitmap.CompressFormat.JPEG, 90, output)
            recycle()
        }
        output.toByteArray()
    }
}
