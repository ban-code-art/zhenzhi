package com.example.myapplication.data.reader.export

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 备份包的 ZIP 封装/解包（P5 导出恢复的"可移动单文件"环节）。
 *
 * 为什么需要它：`BackupPackageWriter` 的产物是一个**目录**（`manifest.json` + `notes/` + `ink/`），
 * 目录没法通过系统分享或文件选择器传给另一台设备/另一次安装。这里把它压成单个 zip，
 * 恢复时再解回目录，交给 `RestoreCoordinator`（它只信任已校验的目录）。
 *
 * 安全边界（与 `BackupPackageVerifier` 同口径，不因为"是我们自己导出的"就放宽）：
 *  - 只接受普通文件，符号链接/设备文件一律拒绝；
 *  - 条目名必须是规范化相对路径：拒绝绝对路径、盘符、`..`、反斜杠、控制字符；
 *  - 解包先写临时目录，全部成功后再 rename；任何一步失败清理残留，绝不半包入库；
 *  - 条目数/单条字节/总字节/压缩比都有上限，防止 zip 炸弹把恢复流程打挂。
 */
class BackupArchive(
    private val limits: Limits = Limits(),
) {
    data class Limits(
        val maxEntries: Int = 5_000,
        val maxEntryBytes: Long = 256L * 1024 * 1024,
        val maxTotalBytes: Long = BackupPackageVerifier.DEFAULT_MAX_PACKAGE_BYTES,
        val maxCompressionRatio: Long = 200L,
    )

    sealed interface Outcome {
        data class Ok(val file: File) : Outcome

        data class Rejected(val code: String, val detail: String) : Outcome
    }

    /** 把一个备份目录压成 zip；目标通常为 `cache/exports/<exportId>.zip`。 */
    fun zipDirectory(directory: File, target: File): Outcome {
        if (!directory.isDirectory) {
            return Outcome.Rejected("INVALID_ARGUMENT", "备份目录不存在：${directory.name}")
        }
        val files = directory.walkTopDown()
            .filter { it.isFile }
            .sortedBy { relativeName(directory, it) }
            .toList()
        if (files.isEmpty()) return Outcome.Rejected("INVALID_ARGUMENT", "备份目录为空")
        if (files.size > limits.maxEntries) {
            return Outcome.Rejected("LIMIT_EXCEEDED", "备份文件数 ${files.size} 超过上限 ${limits.maxEntries}")
        }
        var total = 0L
        files.forEach { file ->
            if (file.length() > limits.maxEntryBytes) {
                return Outcome.Rejected("LIMIT_EXCEEDED", "单个备份文件超过上限：${file.name}")
            }
            total += file.length()
        }
        if (total > limits.maxTotalBytes) {
            return Outcome.Rejected("LIMIT_EXCEEDED", "备份总大小 $total 超过上限 ${limits.maxTotalBytes}")
        }

        val parent = target.absoluteFile.parentFile
            ?: return Outcome.Rejected("INVALID_ARGUMENT", "目标路径无效")
        if (!parent.exists() && !parent.mkdirs()) {
            return Outcome.Rejected("IO_FAILURE", "无法创建导出目录")
        }
        val temporary = File(parent, "${target.name}.tmp")
        return try {
            temporary.delete()
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                files.forEach { file ->
                    val entry = ZipEntry(relativeName(directory, file))
                    entry.time = file.lastModified()
                    zip.putNextEntry(entry)
                    file.inputStream().buffered().use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            if (target.exists()) target.delete()
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            Outcome.Ok(target)
        } catch (error: Exception) {
            temporary.delete()
            Outcome.Rejected("IO_FAILURE", "打包失败：${error.javaClass.simpleName}")
        }
    }

    /** 把 zip 解包到一个新目录（返回目录本身）。同名目标已存在时拒绝，避免覆盖已有导出。 */
    fun extract(zipFile: File, destination: File): Outcome {
        if (!zipFile.isFile) return Outcome.Rejected("INVALID_ARGUMENT", "压缩包不存在")
        if (destination.exists()) return Outcome.Rejected("INVALID_ARGUMENT", "目标目录已存在")

        val root = destination.absoluteFile
        val parent = root.parentFile ?: return Outcome.Rejected("INVALID_ARGUMENT", "目标路径无效")
        if (!parent.exists() && !parent.mkdirs()) {
            return Outcome.Rejected("IO_FAILURE", "无法创建恢复目录")
        }
        val staging = File(parent, "${root.name}.staging")
        staging.deleteRecursively()
        var total = 0L
        var count = 0
        return try {
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().toList()
                if (entries.size > limits.maxEntries) {
                    return Outcome.Rejected("LIMIT_EXCEEDED", "压缩包条目数 ${entries.size} 超过上限 ${limits.maxEntries}")
                }
                entries.forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val name = normalizeEntryName(entry.name)
                        ?: return Outcome.Rejected("PATH_ESCAPE", "压缩包含非法路径：${entry.name}")
                    // manifest.json 不在 files 清单里（它是清单本身），白名单函数按"包内数据文件"口径
                    // 明确排除它，所以这里单独放行；它的合法性由 BackupManifestCodec/Verifier 校验。
                    val allowed = name == BackupSchema.MANIFEST_FILE || BackupPaths.isAllowedPackagePath(name)
                    if (!allowed) {
                        return Outcome.Rejected("PATH_NOT_ALLOWED", "压缩包含不允许的路径：$name")
                    }
                    if (entry.size > limits.maxEntryBytes) {
                        return Outcome.Rejected("LIMIT_EXCEEDED", "压缩包内文件超过上限：$name")
                    }
                    val compressed = entry.compressedSize
                    if (compressed > 0 && entry.size > 0 && entry.size / compressed > limits.maxCompressionRatio) {
                        return Outcome.Rejected("LIMIT_EXCEEDED", "压缩比异常（疑似 zip 炸弹）：$name")
                    }
                    count += 1
                    if (count > limits.maxEntries) {
                        return Outcome.Rejected("LIMIT_EXCEEDED", "压缩包条目数超过上限")
                    }
                    val target = File(staging, name.replace('/', File.separatorChar)).canonicalFile
                    if (!target.path.startsWith(staging.canonicalFile.path + File.separator)) {
                        return Outcome.Rejected("PATH_ESCAPE", "解包路径越界：$name")
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var written = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                written += read
                                total += read
                                if (written > limits.maxEntryBytes) {
                                    return Outcome.Rejected("LIMIT_EXCEEDED", "解包文件超过上限：$name")
                                }
                                if (total > limits.maxTotalBytes) {
                                    return Outcome.Rejected("LIMIT_EXCEEDED", "解包总大小超过上限")
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            if (!File(staging, BackupSchema.MANIFEST_FILE).isFile) {
                staging.deleteRecursively()
                return Outcome.Rejected("MANIFEST_MISSING", "压缩包里没有 ${BackupSchema.MANIFEST_FILE}")
            }
            if (!staging.renameTo(root)) {
                staging.copyRecursively(root, overwrite = false)
                staging.deleteRecursively()
            }
            Outcome.Ok(root)
        } catch (error: java.util.zip.ZipException) {
            staging.deleteRecursively()
            Outcome.Rejected("CORRUPT_INPUT", "压缩包已损坏：${error.message?.take(80)}")
        } catch (error: Exception) {
            staging.deleteRecursively()
            Outcome.Rejected("IO_FAILURE", "解包失败：${error.javaClass.simpleName}")
        }
    }

    /** 规范化条目名：拒绝绝对路径/盘符/`..`/反斜杠/控制字符，其余按 `/` 分隔返回。 */
    private fun normalizeEntryName(raw: String): String? {
        if (raw.isBlank()) return null
        val unified = raw.replace('\\', '/')
        if (unified.startsWith("/")) return null
        if (unified.length >= 2 && unified[1] == ':') return null
        if (unified.any { it.code < 0x20 }) return null
        val parts = unified.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.isEmpty()) return null
        if (parts.any { it == ".." }) return null
        return parts.joinToString("/")
    }

    private fun relativeName(root: File, file: File): String {
        val rootPath = root.absoluteFile.path
        val path = file.absoluteFile.path
        val relative = if (path.startsWith(rootPath + File.separator)) {
            path.substring(rootPath.length + 1)
        } else {
            file.name
        }
        return relative.replace(File.separatorChar, '/')
    }
}
