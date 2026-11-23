package com.example.myapplication.data.ai.config

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keystore secret 存储。
 *
 * - AES-GCM，随机 IV（每次加密新 IV，不复用）
 * - AAD 绑定 userId + purpose + profileId，隔离不同用户的密文
 * - Room 只存 secret_ref；密文写入应用私有文件
 * - 解密失败要求重新输入，不回退明文
 *
 * 密文文件格式：v1:<base64(iv)>:<base64(ciphertext)>
 */
class SecretStore(context: Context) {
    private val directory = java.io.File(context.applicationContext.filesDir, "ai-secrets")

    init {
        if (!directory.exists()) directory.mkdirs()
    }

    data class SecretRef(
        val userId: Long,
        val purpose: String,
        val profileId: String,
        val version: Int,
    ) {
        fun toRef(): String = "v$version:$userId:$purpose:$profileId"
    }

    sealed interface Result {
        data class Ok(val ref: String) : Result
        data class Failure(val reason: String) : Result
    }

    fun store(userId: Long, purpose: String, profileId: String, plaintext: String): Result {
        if (plaintext.isEmpty()) return Result.Failure("密钥为空")
        return runCatching {
            val version = nextVersion(userId, purpose, profileId)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            cipher.updateAAD(aad(userId, purpose, profileId))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val encoded = "v1:${base64(cipher.iv)}:${base64(ciphertext)}"
            fileFor(userId, purpose, profileId, version).writeText(encoded, Charsets.UTF_8)
            Result.Ok(SecretRef(userId, purpose, profileId, version).toRef())
        }.getOrElse { Result.Failure("密钥写入失败：${it.javaClass.simpleName}") }
    }

    fun load(ref: String): String? {
        val parsed = parseRef(ref) ?: return null
        val file = fileFor(parsed.userId, parsed.purpose, parsed.profileId, parsed.version)
        if (!file.exists()) return null
        return runCatching {
            val parts = file.readText(Charsets.UTF_8).split(':')
            if (parts.size != 3 || parts[0] != "v1") return null
            val iv = unbase64(parts[1])
            val ciphertext = unbase64(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad(parsed.userId, parsed.purpose, parsed.profileId))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    fun delete(ref: String): Boolean {
        val parsed = parseRef(ref) ?: return false
        return fileFor(parsed.userId, parsed.purpose, parsed.profileId, parsed.version).delete()
    }

    /**
     * 引用扫描：回收未被 Room 引用的 secret 文件（崩溃后收敛）。
     * [referencedRefs] 是 Room 中所有 ai_profiles.secret_ref 的集合；
     * 只有“当前 profile 引用”与“活跃 run 的 secretVersionRef”都不指向的文件才删除。
     */
    fun gc(referencedRefs: Set<String>): List<String> {
        val liveFiles = referencedRefs.mapNotNull { ref ->
            parseRef(ref)?.let { fileFor(it.userId, it.purpose, it.profileId, it.version).name }
        }.toSet()
        val removed = mutableListOf<String>()
        directory.listFiles()?.forEach { file ->
            if (!file.name.endsWith(".secret")) return@forEach
            if (file.name !in liveFiles) {
                if (file.delete()) refForFileName(file.name)?.let(removed::add)
            }
        }
        return removed
    }

    private fun nextVersion(userId: Long, purpose: String, profileId: String): Int {
        val marker = java.io.File(directory, "${baseName(userId, purpose, profileId)}.version")
        val current = marker.takeIf { it.exists() }?.readText()?.toIntOrNull() ?: 0
        val next = current + 1
        marker.writeText(next.toString())
        return next
    }

    private fun baseName(userId: Long, purpose: String, profileId: String): String =
        "$userId-$purpose-$profileId"

    /**
     * 每个 secretVersion 独立文件：旧版本可供在途 run 继续使用，
     * 新版本写入不覆盖旧密文；GC 按引用集合精确回收。
     */
    private fun fileFor(userId: Long, purpose: String, profileId: String, version: Int): java.io.File =
        java.io.File(directory, "${baseName(userId, purpose, profileId)}-v$version.secret")

    /** 文件名 -> 规范 ref；解析失败返回 null（不把无法识别的文件当回收结果）。 */
    private fun refForFileName(name: String): String? {
        val base = name.removeSuffix(".secret")
        val marker = base.lastIndexOf("-v")
        if (marker <= 0) return null
        val version = base.substring(marker + 2).toIntOrNull() ?: return null
        val prefix = base.substring(0, marker)
        val firstDash = prefix.indexOf('-')
        if (firstDash <= 0) return null
        val userId = prefix.substring(0, firstDash).toLongOrNull() ?: return null
        val rest = prefix.substring(firstDash + 1)
        val secondDash = rest.indexOf('-')
        if (secondDash <= 0) return null
        val purpose = rest.substring(0, secondDash)
        val profileId = rest.substring(secondDash + 1)
        if (profileId.isEmpty()) return null
        return SecretRef(userId, purpose, profileId, version).toRef()
    }

    private fun parseRef(ref: String): SecretRef? {
        val parts = ref.split(':')
        if (parts.size != 4) return null
        val version = parts[0].removePrefix("v").toIntOrNull() ?: return null
        val userId = parts[1].toLongOrNull() ?: return null
        return SecretRef(userId, parts[2], parts[3], version)
    }

    private fun aad(userId: Long, purpose: String, profileId: String): ByteArray =
        "aculearn|$userId|$purpose|$profileId".toByteArray(Charsets.UTF_8)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun base64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun unbase64(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "aculearn.ai.secret.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
