package com.framework.innolive.feature.face

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ReferenceFaceApiTest {
    private val jsonMediaType = "application/json".toMediaType()
    private val image = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun sendsReferenceFaceAsMultipartToAbsoluteEndpointWithoutSessionHeaders() {
        val requests = mutableListOf<Request>()
        val client = clientWithResponse { request ->
            requests += request
            response(request, 201, "{\"registered\":true,\"count\":2}")
        }
        val api = ReferenceFaceApi("https://example.com/api", client)

        try {
            val result = runBlocking {
                api.register(image, "access-token") { error("refresh must not run") }
            }

            assertTrue(result.registered)
            assertEquals(2, result.count)
            assertEquals(1, requests.size)
            val request = requests.single()
            assertEquals("https", request.url.scheme)
            assertEquals("/reference-face", request.url.encodedPath)
            assertEquals("POST", request.method)
            assertEquals("Bearer access-token", request.header("Authorization"))
            assertNotNull(request.body)
            assertTrue(request.body!!.contentType().toString().startsWith("multipart/form-data"))
            assertTrue(
                request.headers.names().none { name ->
                    val normalized = name.lowercase()
                    normalized.contains("session") || normalized.contains("client")
                },
            )

            val body = requestBodyBytes(request)
            val bodyText = body.toString(Charsets.ISO_8859_1)
            assertTrue(bodyText.contains("name=\"image\""))
            assertTrue(bodyText.contains("filename=\"reference-face.jpg\""))
            assertTrue(bodyText.contains("Content-Type: image/jpeg"))
            assertTrue(body.containsSubsequence(image))
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun registerReadsReturnedFaceMetadataForLocalImageAssociation() {
        val client = clientWithResponse { request ->
            response(
                request,
                201,
                """
                    {
                      "registered": true,
                      "count": 1,
                      "faces": [{"face_id": "face-1", "registered_at": "2026-09-08T00:00:00Z"}]
                    }
                """.trimIndent(),
            )
        }
        val api = ReferenceFaceApi("https://example.com", client)

        try {
            val result = runBlocking {
                api.register(image, "access-token") { error("refresh must not run") }
            }

            assertEquals(
                listOf(ReferenceFace("face-1", "2026-09-08T00:00:00Z")),
                result.faces,
            )
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun appendsMultipleImagesUsingTheImagesMultipartField() {
        val requests = mutableListOf<Request>()
        val firstImage = byteArrayOf(1, 2, 3)
        val secondImage = byteArrayOf(4, 5, 6)
        val client = clientWithResponse { request ->
            requests += request
            response(
                request,
                201,
                """
                    {
                      "registered": true,
                      "count": 2,
                      "faces": [
                        {"face_id": "face-1"},
                        {"face_id": "face-2"}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val api = ReferenceFaceApi("https://example.com", client)

        try {
            val result = runBlocking {
                api.append(listOf(firstImage, secondImage), "access-token") {
                    error("refresh must not run")
                }
            }

            assertEquals(2, result.faces.size)
            val request = requests.single()
            assertEquals("POST", request.method)
            assertEquals("/reference-face", request.url.encodedPath)
            val body = requestBodyBytes(request)
            val bodyText = body.toString(Charsets.ISO_8859_1)
            assertEquals(2, bodyText.split("name=\"images\"").size - 1)
            assertTrue(bodyText.contains("filename=\"reference-face-1.jpg\""))
            assertTrue(bodyText.contains("filename=\"reference-face-2.jpg\""))
            assertTrue(body.containsSubsequence(firstImage))
            assertTrue(body.containsSubsequence(secondImage))
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun refreshesOnceAfterFirst401AndRetriesWithTheNewBearerToken() {
        val requests = mutableListOf<Request>()
        val callCount = AtomicInteger()
        val client = clientWithResponse { request ->
            requests += request
            if (callCount.getAndIncrement() == 0) {
                response(request, 401, "{\"error\":{\"code\":\"authentication_error\"}}")
            } else {
                response(request, 201, "{\"registered\":true}")
            }
        }
        val api = ReferenceFaceApi("https://example.com", client)
        var refreshCount = 0

        try {
            val result = runBlocking {
                api.register(image, "old-token") {
                    refreshCount += 1
                    "new-token"
                }
            }

            assertTrue(result.registered)
            assertEquals(2, requests.size)
            assertEquals("Bearer old-token", requests[0].header("Authorization"))
            assertEquals("Bearer new-token", requests[1].header("Authorization"))
            assertEquals(1, refreshCount)
            assertTrue(requestBodyBytes(requests[0]).containsSubsequence(image))
            assertTrue(requestBodyBytes(requests[1]).containsSubsequence(image))
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun second401StopsAfterExactlyOneRefresh() {
        val callCount = AtomicInteger()
        val client = clientWithResponse { request ->
            callCount.incrementAndGet()
            response(request, 401, "{\"error\":{\"code\":\"authentication_error\"}}")
        }
        val api = ReferenceFaceApi("https://example.com", client)
        var refreshCount = 0

        try {
            val exception = assertThrows(ReferenceFaceApiException::class.java) {
                runBlocking {
                    api.register(image, "old-token") {
                        refreshCount += 1
                        "new-token"
                    }
                }
            }

            assertEquals(401, exception.statusCode)
            assertEquals(2, callCount.get())
            assertEquals(1, refreshCount)
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun rejectsSuccessfulResponsesWithoutBooleanRegisteredTrue() {
        listOf(
            "{\"registered\":false}",
            "{\"registered\":\"true\"}",
        ).forEach { responseBody ->
            val client = clientWithResponse { request -> response(request, 200, responseBody) }
            val api = ReferenceFaceApi("https://example.com", client)
            try {
                val exception = assertThrows(ReferenceFaceApiException::class.java) {
                    runBlocking {
                        api.register(image, "access-token") { error("refresh must not run") }
                    }
                }
                assertEquals(200, exception.statusCode)
                assertEquals("reference_rejected", exception.code)
            } finally {
                closeClient(api, client)
            }
        }
    }

    @Test
    fun mapsAiDisabledAnd502ErrorEnvelopes() {
        val cases = listOf(
            Triple(
                400,
                "{\"error\":{\"code\":\"bad_request\",\"details\":{\"reason\":\"ai_disabled\"}}}",
                "bad_request",
            ),
            Triple(
                502,
                "{\"error\":{\"code\":\"ai_unavailable\"}}",
                "ai_unavailable",
            ),
        )

        cases.forEach { (statusCode, responseBody, expectedCode) ->
            val client = clientWithResponse { request -> response(request, statusCode, responseBody) }
            val api = ReferenceFaceApi("https://example.com", client)
            try {
                val exception = assertThrows(ReferenceFaceApiException::class.java) {
                    runBlocking {
                        api.register(image, "access-token") { error("refresh must not run") }
                    }
                }
                assertEquals(statusCode, exception.statusCode)
                assertEquals(expectedCode, exception.code)
                if (statusCode == 400) assertEquals("ai_disabled", exception.detailsReason)
            } finally {
                closeClient(api, client)
            }
        }
    }

    @Test
    fun cancellationCancelsTheUnderlyingOkHttpCall() {
        val requestStarted = CountDownLatch(1)
        val cancellationObserved = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestStarted.countDown()
                try {
                    while (!chain.call().isCanceled()) {
                        Thread.sleep(5)
                    }
                } catch (_: InterruptedException) {
                    // Cancellation may interrupt the interceptor thread before the next poll.
                } finally {
                    if (chain.call().isCanceled()) cancellationObserved.countDown()
                }
                throw IOException("cancelled by test")
            }
            .build()
        val api = ReferenceFaceApi("https://example.com", client)

        try {
            runBlocking {
                val requestJob: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        api.register(image, "access-token") { error("refresh must not run") }
                    } catch (_: CancellationException) {
                        // Expected when the suspend call is cancelled by the test.
                    }
                }
                assertTrue(requestStarted.await(2, TimeUnit.SECONDS))
                requestJob.cancelAndJoin()
            }
            assertTrue(cancellationObserved.await(2, TimeUnit.SECONDS))
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun readsStatusAndParsesReferenceFaceMetadata() {
        val requests = mutableListOf<Request>()
        val client = clientWithResponse { request ->
            requests += request
            response(
                request,
                200,
                """
                    {
                      "registered": true,
                      "source": "api",
                      "registered_at": "2026-08-30T00:00:00Z",
                      "count": 1,
                      "faces": [
                        {"face_id": "face-1", "registered_at": "2026-08-30T00:00:00Z"}
                      ]
                    }
                """.trimIndent(),
            )
        }
        val api = ReferenceFaceApi("https://example.com", client)

        try {
            val status = runBlocking {
                api.getStatus("access-token") { error("refresh must not run") }
            }

            assertTrue(status.registered)
            assertEquals("api", status.source)
            assertEquals("2026-08-30T00:00:00Z", status.registeredAt)
            assertEquals(1, status.count)
            assertEquals(listOf(ReferenceFace("face-1", "2026-08-30T00:00:00Z")), status.faces)
            assertEquals("GET", requests.single().method)
            assertEquals("/reference-face", requests.single().url.encodedPath)
            assertEquals("Bearer access-token", requests.single().header("Authorization"))
            assertNoSessionOrClientHeaders(requests)
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun deletesAllAndIndividualFacesWith204AndEncodedFaceId() {
        val requests = mutableListOf<Request>()
        val client = clientWithResponse { request ->
            requests += request
            response(request, 204, "")
        }
        val api = ReferenceFaceApi("https://example.com/api", client)

        try {
            runBlocking {
                api.deleteFace("face/1", "access-token") { error("refresh must not run") }
                api.deleteAll("access-token") { error("refresh must not run") }
            }

            assertEquals(2, requests.size)
            assertEquals("DELETE", requests[0].method)
            assertEquals("/reference-face/face%2F1", requests[0].url.encodedPath)
            assertEquals("DELETE", requests[1].method)
            assertEquals("/reference-face", requests[1].url.encodedPath)
            assertNoSessionOrClientHeaders(requests)
        } finally {
            closeClient(api, client)
        }
    }


    // --- reference-face-v1.md: 401 뒤 한 번 갱신·재시도, 오류 코드 매핑 ---

    @Test
    fun appendStatusAndDeleteRequestsRefreshOnceAfter401Too() {
        val operations: List<Pair<String, suspend ReferenceFaceApi.(suspend () -> String) -> Unit>> = listOf(
            "append" to { refresh -> append(listOf(image), "old-token", refresh); Unit },
            "status" to { refresh -> getStatus("old-token", refresh); Unit },
            "deleteAll" to { refresh -> deleteAll("old-token", refresh) },
            "deleteFace" to { refresh -> deleteFace("face-1", "old-token", refresh) },
        )

        operations.forEach { (name, operation) ->
            val requests = mutableListOf<Request>()
            val client = clientWithResponse { request ->
                requests += request
                when {
                    requests.size == 1 -> response(request, 401, "")
                    request.method == "DELETE" -> response(request, 204, "")
                    else -> response(request, 200, "{\"registered\":true}")
                }
            }
            val api = ReferenceFaceApi("https://example.com", client)
            var refreshCount = 0
            try {
                runBlocking {
                    api.operation {
                        refreshCount += 1
                        "new-token"
                    }
                }

                assertEquals(name, 2, requests.size)
                assertEquals(name, listOf("Bearer old-token", "Bearer new-token"), requests.map { it.header("Authorization") })
                assertEquals(name, 1, refreshCount)
            } finally {
                closeClient(api, client)
            }
        }
    }

    @Test
    fun refreshFailureOrBlankTokenBecomesAuthenticationErrorWithoutASecondRequest() {
        val refreshes: List<suspend () -> String> = listOf(
            { throw IOException("refresh unavailable") },
            { "   " },
        )

        refreshes.forEach { refresh ->
            val callCount = AtomicInteger()
            val client = clientWithResponse { request ->
                callCount.incrementAndGet()
                response(request, 401, "{\"error\":{\"code\":\"authentication_error\"}}")
            }
            val api = ReferenceFaceApi("https://example.com", client)
            try {
                val exception = assertThrows(ReferenceFaceApiException::class.java) {
                    runBlocking { api.getStatus("old-token", refresh) }
                }

                assertEquals(401, exception.statusCode)
                assertEquals("authentication_error", exception.code)
                assertEquals(1, callCount.get())
            } finally {
                closeClient(api, client)
            }
        }
    }

    @Test
    fun mapsNotFoundConflictNetworkAndNonJsonResponses() {
        data class Case(val status: Int?, val body: String, val expectedCode: String?, val delete: Boolean = false)
        val cases = listOf(
            Case(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"missing face\"}}", "not_found", delete = true),
            Case(409, "{\"error\":{\"code\":\"withdrawal_in_progress\"}}", "withdrawal_in_progress"),
            Case(401, "", "authentication_error"),
            Case(502, "", "ai_unavailable"),
            Case(200, "<html>", "invalid_response"),
            // delete는 204만 성공입니다.
            Case(200, "{\"registered\":true}", null, delete = true),
            Case(null, "", null),
        )

        cases.forEach { case ->
            val client = if (case.status == null) {
                OkHttpClient.Builder().addInterceptor { throw IOException("unreachable") }.build()
            } else {
                clientWithResponse { request -> response(request, case.status, case.body) }
            }
            val api = ReferenceFaceApi("https://example.com", client)
            try {
                val exception = assertThrows(ReferenceFaceApiException::class.java) {
                    runBlocking {
                        if (case.delete) api.deleteFace("face-1", "access-token") { "new-token" }
                        else api.getStatus("access-token") { "new-token" }
                    }
                }

                assertEquals("$case", case.status, exception.statusCode)
                assertEquals("$case", case.expectedCode, exception.code)
                if (case.status == 404) assertEquals("missing face", exception.message)
            } finally {
                closeClient(api, client)
            }
        }
    }

    @Test
    fun appendRejectsEmptyBlankAndOversizedBatchesBeforeSending() {
        val requests = AtomicInteger()
        val client = clientWithResponse { request ->
            requests.incrementAndGet()
            response(request, 201, "{\"registered\":true}")
        }
        val api = ReferenceFaceApi("https://example.com", client)

        try {
            for (images in listOf(emptyList(), listOf(byteArrayOf()), List(21) { image })) {
                assertThrows(IllegalArgumentException::class.java) {
                    runBlocking { api.append(images, "access-token") { "new-token" } }
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { api.deleteFace("  ", "access-token") { "new-token" } }
            }
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { api.getStatus("  ") { "new-token" } }
            }
            assertEquals(0, requests.get())

            runBlocking { api.append(List(20) { image }, "access-token") { "new-token" } }
            assertEquals(1, requests.get())
        } finally {
            closeClient(api, client)
        }
    }

    @Test
    fun statusSkipsBlankFaceIdsAndLeavesMissingCountNull() {
        val client = clientWithResponse { request ->
            response(
                request,
                200,
                """{"registered":true,"faces":[{"face_id":"  "},{"face_id":"face-2"},{"registered_at":"x"},null]}""",
            )
        }
        val api = ReferenceFaceApi("https://example.com", client)

        try {
            val status = runBlocking { api.getStatus("access-token") { error("refresh must not run") } }

            assertTrue(status.registered)
            assertEquals(null, status.count)
            assertEquals(null, status.source)
            assertEquals(listOf(ReferenceFace("face-2", null)), status.faces)
        } finally {
            closeClient(api, client)
        }
    }

    private fun clientWithResponse(
        responder: (Request) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            responder(chain.request())
        })
        .build()

    private fun response(request: Request, statusCode: Int, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message("test")
            .body(body.toResponseBody(jsonMediaType))
            .build()

    private fun closeClient(api: ReferenceFaceApi, client: OkHttpClient) {
        api.close()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdownNow()
    }

    private fun requestBodyBytes(request: Request): ByteArray = Buffer().also { buffer ->
        request.body!!.writeTo(buffer)
    }.readByteArray()

    private fun assertNoSessionOrClientHeaders(requests: List<Request>) {
        requests.forEach { request ->
            assertTrue(
                request.headers.names().none { name ->
                    val normalized = name.lowercase()
                    normalized.contains("session") || normalized.contains("client")
                },
            )
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean =
        indices.any { start ->
            start + needle.size <= size &&
                needle.indices.all { offset -> this[start + offset] == needle[offset] }
        }
}
