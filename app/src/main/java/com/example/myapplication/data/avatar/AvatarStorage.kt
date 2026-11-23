package com.example.myapplication.data.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

object AvatarStorage {

    private const val TARGET_SIZE = 512

    /** Writes one independent candidate. The caller removes old photos only after persistence. */
    fun saveFromUri(context: Context, uri: Uri, userId: Long): String? {
        if (userId <= 0L) return null
        var decoded: Bitmap? = null
        var square: Bitmap? = null
        var scaled: Bitmap? = null
        var temporary: File? = null
        var destination: File? = null
        var completed = false
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    TARGET_SIZE * 2
                )
            }
            val source = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null
            decoded = source

            val cropped = if (source.width == source.height) {
                source
            } else {
                centerCropSquare(source)
            }
            square = cropped
            val output = if (cropped.width == TARGET_SIZE && cropped.height == TARGET_SIZE) {
                cropped
            } else {
                Bitmap.createScaledBitmap(cropped, TARGET_SIZE, TARGET_SIZE, true)
            }
            scaled = output

            val dir = File(context.filesDir, "avatars")
            if (!dir.isDirectory && !dir.mkdirs()) return null
            val file = File(dir, "avatar_${userId}_${UUID.randomUUID()}.jpg")
            val staging = File(dir, file.name + ".tmp")
            temporary = staging
            destination = file
            FileOutputStream(staging).use { out ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, 90, out)) return null
                out.flush()
                out.fd.sync()
            }
            if (!staging.renameTo(file)) return null
            completed = true
            file.name
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        } finally {
            temporary?.let { runCatching { it.delete() } }
            if (!completed) destination?.let { runCatching { it.delete() } }
            listOfNotNull(scaled, square, decoded).distinct().forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    /** Deletes only an exact generated file owned by this user in the private avatar directory. */
    fun deleteStored(context: Context, stored: String?, userId: Long): Boolean =
        deleteManagedFile(File(context.filesDir, "avatars"), stored, userId)

    internal fun deleteManagedFile(directory: File, stored: String?, userId: Long): Boolean {
        if (stored.isNullOrBlank()) return true
        if (userId <= 0L) return false
        return try {
            val input = File(stored)
            val file = if (input.isAbsolute) input else File(directory, stored)
            val canonical = file.canonicalFile
            if (canonical.parentFile != directory.canonicalFile ||
                !canonical.name.matches(Regex("avatar_${userId}_[A-Za-z0-9-]+\\.jpg")) ||
                (file.exists() && !file.isFile)
            ) return false
            !file.exists() || file.delete()
        } catch (_: Exception) {
            false
        }
    }

    fun resolve(context: Context, stored: String): File {
        val direct = File(stored)
        return if (direct.isAbsolute) {
            direct
        } else {
            File(File(context.filesDir, "avatars"), stored)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var w = width
        var h = height
        while (maxOf(w, h) / 2 >= target) {
            w /= 2
            h /= 2
            sample *= 2
        }
        return sample
    }

    private fun centerCropSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        val x = (bitmap.width - side) / 2
        val y = (bitmap.height - side) / 2
        return Bitmap.createBitmap(bitmap, x, y, side, side)
    }
}
