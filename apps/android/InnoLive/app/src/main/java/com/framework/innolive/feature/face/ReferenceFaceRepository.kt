package com.framework.innolive.feature.face

import android.content.Context
import android.graphics.Bitmap
import com.framework.innolive.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class ReferenceFaceSnapshot(
    val status: ReferenceFaceStatus,
    val images: Map<String, Bitmap>,
)

internal data class ReferenceFaceAppendResult(
    val registration: ReferenceFaceRegistrationResult,
    val localImageStoreFailed: Boolean,
)

/** Coordinates the account-scoped face API with its matching local image cache. */
internal class ReferenceFaceRepository(
    context: Context,
    private val apiFactory: () -> ReferenceFaceApi = {
        ReferenceFaceApi(BuildConfig.INNOLIVE_SERVER_URL)
    },
) : AutoCloseable {
    private val imageStore = ReferenceFaceImageStore(context.applicationContext)
    private var api: ReferenceFaceApi? = null

    suspend fun loadStatus(
        accountEmail: String,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): ReferenceFaceSnapshot {
        val status = api().getStatus(accessToken, refreshAccessToken)
        val images = if (accountEmail.isBlank()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                status.faces.mapNotNull { face ->
                    imageStore.load(accountEmail, face.faceId)?.let { face.faceId to it }
                }.toMap()
            }
        }
        return ReferenceFaceSnapshot(status, images)
    }

    fun ensureApiAvailable() {
        api()
    }

    suspend fun append(
        images: List<ByteArray>,
        existingFaces: List<ReferenceFace>,
        accountEmail: String,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): ReferenceFaceAppendResult {
        val registration = api().append(images, accessToken, refreshAccessToken)
        if (accountEmail.isBlank()) {
            return ReferenceFaceAppendResult(registration, localImageStoreFailed = false)
        }

        val existingFaceIds = existingFaces.mapTo(mutableSetOf()) { it.faceId }
        val newFaces = registration.faces.filterNot { it.faceId in existingFaceIds }
        val localImageStoreFailed = try {
            withContext(Dispatchers.IO) {
                check(newFaces.size == images.size) {
                    "The server did not return appended face metadata."
                }
                newFaces.forEachIndexed { index, face ->
                    imageStore.append(
                        accountEmail = accountEmail,
                        faceId = face.faceId,
                        jpeg = images[index],
                    )
                }
            }
            false
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            true
        }
        return ReferenceFaceAppendResult(registration, localImageStoreFailed)
    }

    suspend fun deleteFace(
        faceId: String,
        accountEmail: String,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): Boolean {
        api().deleteFace(faceId, accessToken, refreshAccessToken)
        return deleteLocalFace(accountEmail, faceId)
    }

    suspend fun deleteAll(
        accountEmail: String,
        accessToken: String,
        refreshAccessToken: suspend () -> String,
    ): Boolean {
        api().deleteAll(accessToken, refreshAccessToken)
        return deleteLocalFaces(accountEmail)
    }

    suspend fun deleteLocalFace(accountEmail: String, faceId: String): Boolean = try {
        withContext(Dispatchers.IO) { imageStore.delete(accountEmail, faceId) }
        false
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        true
    }

    suspend fun deleteLocalFaces(accountEmail: String): Boolean = try {
        withContext(Dispatchers.IO) { imageStore.deleteAll(accountEmail) }
        false
    } catch (exception: CancellationException) {
        throw exception
    } catch (_: Exception) {
        true
    }

    override fun close() {
        api?.close()
        api = null
    }

    private fun api(): ReferenceFaceApi = api ?: apiFactory().also { api = it }
}
