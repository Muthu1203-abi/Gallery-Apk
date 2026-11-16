package com.GalleryYou.Pixel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Properties
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object HiddenVault {
    private const val KEY_ALIAS = "pixel_gallery_hidden_album"
    private const val IV_SIZE = 12

    fun store(context: Context, items: List<MediaFile>): List<MediaFile> {
        val stored = ArrayList<MediaFile>(items.size)
        try {
            items.forEach { item ->
                val id = UUID.randomUUID().toString()
                storeOne(context, item, id)
                stored += item.copy(vaultId = id, canModify = false)
            }
        } catch (e: IOException) {
            discard(context, stored)
            throw e
        } catch (e: GeneralSecurityException) {
            discard(context, stored)
            throw IOException("Could not encrypt hidden media", e)
        } catch (e: SecurityException) {
            discard(context, stored)
            throw IOException("Could not read selected media", e)
        }
        return stored
    }

    fun unlock(context: Context): List<MediaFile> {
        clearDecryptedCache(context)
        val folder = vaultDirectory(context)
        val metadataFiles = folder.listFiles { file -> file.extension == "meta" } ?: return emptyList()
        val key = getKey()
        val cache = decryptedDirectory(context).apply { if (!exists() && !mkdirs()) throw IOException("Could not create hidden media cache") }

        return metadataFiles.sortedBy { it.name }.map { metadataFile ->
            val properties = Properties()
            FileInputStream(metadataFile).use { properties.load(it) }
            val id = metadataFile.nameWithoutExtension
            val mime = properties.getProperty("mime") ?: throw IOException("Hidden media metadata is missing")
            val isVideo = mime.startsWith("video/")
            if (!isVideo && !mime.startsWith("image/")) throw IOException("Hidden media type is invalid")
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "bin"
            val output = File(cache, "$id.$extension")
            decrypt(File(folder, "$id.enc"), output, key)
            MediaFile(
                id = properties.getProperty("id")?.toLongOrNull() ?: -1L,
                uri = Uri.fromFile(output),
                isVideo = isVideo,
                name = properties.getProperty("name") ?: output.name,
                dateMillis = properties.getProperty("dateMillis")?.toLongOrNull() ?: 0L,
                durationMs = properties.getProperty("durationMs")?.toLongOrNull() ?: 0L,
                size = properties.getProperty("size")?.toLongOrNull() ?: output.length(),
                width = properties.getProperty("width")?.toIntOrNull() ?: 0,
                height = properties.getProperty("height")?.toIntOrNull() ?: 0,
                mime = mime,
                bucketId = properties.getProperty("bucketId")?.toLongOrNull() ?: 0L,
                bucket = properties.getProperty("bucket") ?: "",
                relativePath = properties.getProperty("relativePath") ?: "",
                canModify = false,
                vaultId = id,
            )
        }
    }

    fun restore(context: Context, items: List<MediaFile>): Int {
        var restored = 0
        items.forEach { item ->
            val id = item.vaultId ?: return@forEach
            restoreOne(context, item)
            removeStored(context, id)
            restored++
        }
        return restored
    }

    fun discard(context: Context, items: List<MediaFile>) {
        items.mapNotNull { it.vaultId }.forEach { removeStored(context, it) }
    }

    fun clearDecryptedCache(context: Context) {
        val cache = decryptedDirectory(context)
        if (cache.exists() && !cache.deleteRecursively()) {
            Log.e("HiddenVault", "Could not clear decrypted hidden media cache")
        }
    }

    private fun storeOne(context: Context, item: MediaFile, id: String) {
        val folder = vaultDirectory(context)
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Could not create hidden album")
        val encryptedFile = File(folder, "$id.enc")
        val metadataFile = File(folder, "$id.meta")
        var complete = false
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getKey())
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                FileOutputStream(encryptedFile).use { raw ->
                    raw.write(cipher.iv)
                    CipherOutputStream(raw, cipher).use { encrypted ->
                        input.copyTo(encrypted)
                    }
                }
            } ?: throw IOException("Could not read ${item.name}")
            val properties = Properties().apply {
                setProperty("id", item.id.toString())
                setProperty("name", item.name)
                setProperty("mime", item.mime)
                setProperty("dateMillis", item.dateMillis.toString())
                setProperty("durationMs", item.durationMs.toString())
                setProperty("size", item.size.toString())
                setProperty("width", item.width.toString())
                setProperty("height", item.height.toString())
                setProperty("bucketId", item.bucketId.toString())
                setProperty("bucket", item.bucket)
                setProperty("relativePath", item.relativePath)
            }
            FileOutputStream(metadataFile).use { properties.store(it, null) }
            complete = true
        } catch (e: GeneralSecurityException) {
            throw IOException("Could not encrypt ${item.name}", e)
        } finally {
            if (!complete) {
                encryptedFile.delete()
                metadataFile.delete()
            }
        }
    }

    private fun decrypt(source: File, target: File, key: SecretKey) {
        var complete = false
        try {
            DataInputStream(BufferedInputStream(FileInputStream(source))).use { input ->
                val iv = ByteArray(IV_SIZE)
                input.readFully(iv)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
                CipherInputStream(input, cipher).use { encrypted ->
                    FileOutputStream(target).use { encrypted.copyTo(it) }
                }
            }
            complete = true
        } catch (e: GeneralSecurityException) {
            throw IOException("Could not decrypt hidden media", e)
        } finally {
            if (!complete) target.delete()
        }
    }

    private fun restoreOne(context: Context, item: MediaFile) {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (item.isVideo) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
        } else if (item.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val defaultFolder = if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val relativePath = item.relativePath.ifBlank { "$defaultFolder/Pixel Gallery/" }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, item.mime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }
        val target = resolver.insert(collection, values) ?: throw IOException("Could not restore ${item.name}")
        var complete = false
        try {
            resolver.openInputStream(item.uri)?.use { source ->
                resolver.openOutputStream(target)?.use { output ->
                    source.copyTo(output)
                } ?: throw IOException("Could not save ${item.name}")
            } ?: throw IOException("Could not read ${item.name}")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val ready = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                if (resolver.update(target, ready, null, null) <= 0) {
                    throw IOException("Could not publish restored media")
                }
            }
            complete = true
        } finally {
            if (!complete) resolver.delete(target, null, null)
        }
    }

    private fun getKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun removeStored(context: Context, id: String) {
        val folder = vaultDirectory(context)
        listOf(File(folder, "$id.enc"), File(folder, "$id.meta")).forEach { file ->
            if (file.exists() && !file.delete()) throw IOException("Could not remove hidden media")
        }
    }

    private fun vaultDirectory(context: Context) = File(context.noBackupFilesDir, "hidden_album")
    private fun decryptedDirectory(context: Context) = File(context.cacheDir, "hidden_album_unlocked")
}
