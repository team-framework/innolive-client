package com.framework.innolive.feature.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

internal class ReferenceFaceImageStore(context: Context) {
    private val rootDirectory = File(context.noBackupFilesDir, "reference-faces")

    fun save(accountEmail: String, faceId: String, jpeg: ByteArray) {
        store(accountEmail, faceId, jpeg)

        val accountDirectory = accountDirectory(accountEmail)
        accountDirectory.listFiles()
            ?.filter { file -> file.extension == "jpg" && file.name != imageFile(accountEmail, faceId).name }
            ?.forEach { file ->
                if (!file.delete() && file.exists()) {
                    throw IOException("An old reference face image could not be removed.")
                }
            }
    }

    fun append(accountEmail: String, faceId: String, jpeg: ByteArray) {
        store(accountEmail, faceId, jpeg)
    }

    private fun store(accountEmail: String, faceId: String, jpeg: ByteArray) {
        require(accountEmail.isNotBlank()) { "Account email must not be blank." }
        require(faceId.isNotBlank()) { "Face id must not be blank." }
        require(jpeg.isNotEmpty()) { "Reference face image must not be empty." }

        val accountDirectory = accountDirectory(accountEmail)
        check(accountDirectory.mkdirs() || accountDirectory.isDirectory) {
            "Reference face directory could not be created."
        }
        val target = imageFile(accountEmail, faceId)
        val temporary = File(accountDirectory, "${target.name}.tmp")
        try {
            temporary.outputStream().use { output -> output.write(jpeg) }
            check(temporary.renameTo(target)) { "Reference face image could not be stored." }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    fun load(accountEmail: String, faceId: String): Bitmap? {
        if (accountEmail.isBlank() || faceId.isBlank()) return null
        val file = imageFile(accountEmail, faceId)
        return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    fun delete(accountEmail: String, faceId: String) {
        if (accountEmail.isBlank() || faceId.isBlank()) return
        val file = imageFile(accountEmail, faceId)
        if (file.exists() && !file.delete()) {
            throw IOException("Reference face image could not be removed.")
        }
    }

    fun deleteAll(accountEmail: String) {
        if (accountEmail.isBlank()) return
        val directory = accountDirectory(accountEmail)
        if (!directory.isDirectory) return
        directory.listFiles()
            ?.filter { file -> file.extension == "jpg" }
            ?.forEach { file ->
                if (!file.delete() && file.exists()) {
                    throw IOException("Reference face image could not be removed.")
                }
            }
    }

    private fun accountDirectory(accountEmail: String): File =
        File(rootDirectory, digest(accountEmail.trim().lowercase(Locale.ROOT)))

    private fun imageFile(accountEmail: String, faceId: String): File =
        File(accountDirectory(accountEmail), "${digest(faceId)}.jpg")

    private fun digest(value: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
