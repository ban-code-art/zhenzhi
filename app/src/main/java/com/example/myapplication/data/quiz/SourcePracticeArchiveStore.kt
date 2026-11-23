package com.example.myapplication.data.quiz

import java.io.File
import java.io.FileOutputStream

/** Same-directory replace with a recoverable previous file; works on Android 24 and on the JVM. */
internal class SourcePracticeArchiveStore(filesDir: File) {
    private val root = File(filesDir, "source-practice-archive/v1").canonicalFile
    private val recordName = Regex("[a-f0-9]{64}\\.json(?:\\.bak)?")

    fun readAll(userId: Long): List<SourcePracticeRecord> {
        val directory = directory(userId)
        if (!directory.exists()) return emptyList()
        val names = directory.listFiles()?.filter { it.isFile && recordName.matches(it.name) }
            ?.map { it.name.removeSuffix(".bak").removeSuffix(".json") }?.distinct()
            ?: throw SourcePracticeArchiveException("暂时无法读取随堂记录。")
        return names.mapNotNull { id ->
            try { read(userId, id) } catch (_: SourcePracticeArchiveException) { null }
        }
    }

    fun read(userId: Long, recordId: String): SourcePracticeRecord? {
        val target = target(userId, recordId)
        recover(target)
        if (!target.exists()) return null
        val record = decodeFile(target)
        if (record.ownerUserId != userId || record.id != recordId)
            throw SourcePracticeArchiveException("这份记录不属于当前用户。")
        return record
    }

    fun write(record: SourcePracticeRecord) {
        SourcePracticeArchiveCodec.validate(record)
        val target = target(record.ownerUserId, record.id)
        val directory = checkNotNull(target.parentFile)
        if (!directory.isDirectory && !directory.mkdirs()) throw SourcePracticeArchiveException("暂时无法保存随堂记录。")
        recover(target)
        val temporary = File(directory, target.name + ".tmp")
        val backup = File(directory, target.name + ".bak")
        val bytes = SourcePracticeArchiveCodec.encode(record).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) throw SourcePracticeArchiveException("这组练习内容过大，无法保存，请按章节练习。")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            if (target.exists() && !target.renameTo(backup)) throw SourcePracticeArchiveException("暂时无法更新随堂记录。")
            if (!temporary.renameTo(target)) {
                if (backup.exists() && !backup.renameTo(target))
                    throw SourcePracticeArchiveException("随堂记录更新未完成，下次打开将恢复上次内容。")
                throw SourcePracticeArchiveException("暂时无法保存随堂记录，请重试。")
            }
            if (backup.exists() && !backup.delete()) throw SourcePracticeArchiveException("随堂记录更新未完成，请重试。")
        } catch (error: SourcePracticeArchiveException) { throw error }
        catch (error: Exception) { throw SourcePracticeArchiveException("保存随堂记录失败，请重试。", error) }
        finally { if (temporary.exists()) temporary.delete() }
    }

    private fun recover(target: File) {
        val backup = File(target.parentFile, target.name + ".bak")
        if (!backup.exists()) return
        // A backup means the previous replace did not finish its commit; validate before restoring it.
        try { decodeFile(backup) }
        catch (error: SourcePracticeArchiveException) {
            if (!target.exists()) throw error
            decodeFile(target)
            if (!backup.delete()) throw SourcePracticeArchiveException("暂时无法恢复随堂记录。")
            return
        }
        if (target.exists() && !target.delete()) throw SourcePracticeArchiveException("暂时无法恢复随堂记录。")
        if (!backup.renameTo(target)) throw SourcePracticeArchiveException("暂时无法恢复随堂记录。")
    }

    private fun decodeFile(file: File): SourcePracticeRecord {
        if (!file.isFile || file.length() > MAX_FILE_BYTES) throw SourcePracticeArchiveException("这份随堂记录不完整或内容过大。")
        return try { SourcePracticeArchiveCodec.decode(file.readText(Charsets.UTF_8)) }
        catch (error: SourcePracticeArchiveException) { throw error }
        catch (error: Exception) { throw SourcePracticeArchiveException("读取随堂记录失败，请重试。", error) }
    }

    private fun directory(userId: Long): File {
        if (userId <= 0) throw SourcePracticeArchiveException("请先登录后使用随堂记录。")
        return File(root, "user-$userId")
    }

    private fun target(userId: Long, recordId: String): File {
        if (!recordId.matches(Regex("[a-f0-9]{64}"))) throw SourcePracticeArchiveException("随堂记录编号无效。")
        return File(directory(userId), "$recordId.json")
    }

    companion object { private const val MAX_FILE_BYTES = 8L * 1024 * 1024 }
}
