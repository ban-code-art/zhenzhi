package com.example.myapplication.data.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object AvatarStorage {

    private const val TARGET_SIZE = 512

    fun saveFromUri(context: Context, uri: Uri, userId: Long): String? {
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
            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val square = if (decoded.width == decoded.height) {
                decoded
            } else {
                centerCropSquare(decoded)
            }
            val scaled = if (square.width == TARGET_SIZE && square.height == TARGET_SIZE) {
                square
            } else {
                Bitmap.createScaledBitmap(square, TARGET_SIZE, TARGET_SIZE, true)
            }

            val dir = File(context.filesDir, "avatars").apply { mkdirs() }
            dir.listFiles { file -> file.name.startsWith("avatar_${userId}_") }
                ?.forEach { it.delete() }

            val file = File(dir, "avatar_${userId}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            file.name
        } catch (e: Exception) {
            null
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
        while (w / 2 >= target && h / 2 >= target) {
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
