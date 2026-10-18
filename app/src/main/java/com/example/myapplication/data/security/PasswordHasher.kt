package com.example.myapplication.data.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 120_000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_BYTES = 16

    fun generateSalt(): String {
        val salt = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(salt)
        return salt.toHex()
    }

    fun hash(password: String, saltHex: String): String {
        val spec = PBEKeySpec(
            password.toCharArray(),
            saltHex.hexToBytes(),
            ITERATIONS,
            KEY_LENGTH_BITS
        )
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded.toHex()
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(password: String, saltHex: String, expectedHashHex: String): Boolean {
        val actual = hash(password, saltHex).hexToBytes()
        val expected = expectedHashHex.hexToBytes()
        return MessageDigest.isEqual(actual, expected)
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }

private fun String.hexToBytes(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
