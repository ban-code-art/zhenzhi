package com.example.myapplication.domain.reader

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

class SourceReadLimitExceeded : IOException("SOURCE_IO_BUDGET_EXCEEDED")

/** One serial operation's raw source reads, including validation and repeated reads. */
class SourceReadBudget(
    val maxBytes: Long,
    private val checkActive: () -> Unit = {},
    private val onRead: (Int) -> Unit = {},
) {
    init { require(maxBytes in 1..64L * 1024 * 1024) }
    var bytesRead: Long = 0
        private set
    val remaining: Long get() = maxBytes - bytesRead

    fun checkpoint() = checkActive()

    private fun pump(input: InputStream, expectedBytes: Long?, accept: (ByteArray, Int) -> Unit) {
        checkpoint()
        if (expectedBytes != null && (expectedBytes < 0 || expectedBytes > remaining)) throw SourceReadLimitExceeded()
        val buffer = ByteArray(8192)
        var total = 0L
        while (expectedBytes == null || total < expectedBytes) {
            checkpoint()
            val count = minOf(buffer.size.toLong(), remaining, expectedBytes?.minus(total) ?: Long.MAX_VALUE).toInt()
            if (count <= 0) throw SourceReadLimitExceeded()
            val n = input.read(buffer, 0, count)
            if (n < 0) {
                if (expectedBytes != null) throw IOException("source changed during read")
                break
            }
            if (n == 0) throw IOException("source made no read progress")
            bytesRead += n
            total += n
            onRead(n)
            checkpoint()
            accept(buffer, n)
        }
        checkpoint()
    }

    fun readAll(input: InputStream, expectedBytes: Long? = null): ByteArray {
        val output = ByteArrayOutputStream()
        pump(input, expectedBytes) { bytes, n -> output.write(bytes, 0, n) }
        return output.toByteArray()
    }

    fun readFile(file: File): ByteArray {
        checkpoint()
        val length = file.length()
        if (length > remaining) throw SourceReadLimitExceeded()
        val bytes = file.inputStream().use { readAll(it, length) }
        if (file.length() != length) throw IOException("source changed during read")
        return bytes
    }

    fun hashFile(file: File): String {
        checkpoint()
        val length = file.length()
        if (length > remaining) throw SourceReadLimitExceeded()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> pump(input, length) { bytes, n -> digest.update(bytes, 0, n) } }
        if (file.length() != length) throw IOException("source changed during read")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
