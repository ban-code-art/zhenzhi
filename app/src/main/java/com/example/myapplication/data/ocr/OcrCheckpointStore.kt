package com.example.myapplication.data.ocr

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 逐页检查点文件存储：`<root>/<sha256(documentId)>.json`。
 *
 * - 原子写：先写 `.tmp` 再 rename，进程被杀不会留下半截 JSON；
 * - 坏文件/空文件读取返回 null（下次重新识别），不抛给 UI；
 * - 不写数据库：schema v6 已冻结，OCR 检查点属于派生缓存，可随时重建。
 */
class OcrCheckpointStore(private val root: File) {

    fun load(documentId: String): OcrCheckpoint? {
        val file = fileFor(documentId)
        if (!file.isFile) return null
        val raw = runCatching { file.readText() }.getOrNull() ?: return null
        return OcrCheckpointCodec.decode(raw)
    }

    fun save(checkpoint: OcrCheckpoint) {
        synchronized(lockFor(checkpoint.documentId)) {
            root.mkdirs()
            val target = fileFor(checkpoint.documentId)
            val existing = load(checkpoint.documentId)
                ?.takeIf {
                    it.matches(
                        documentId = checkpoint.documentId,
                        sourceFileHash = checkpoint.sourceFileHash,
                        engineId = checkpoint.engineId,
                        modelVersion = checkpoint.modelVersion,
                        extractorVersion = checkpoint.extractorVersion,
                    )
                }
            val incomingPages = checkpoint.pages.associateBy { it.pageIndex }
            val merged = checkpoint.copy(
                pages = (existing?.pages.orEmpty().associateBy { it.pageIndex } + incomingPages)
                    .map { (pageIndex, incoming) ->
                        val prior = existing?.page(pageIndex)
                        if (prior?.humanVerifiedByUserId != null && prior.humanVerifiedAt != null &&
                            (incoming.humanVerifiedByUserId == null || incoming.humanVerifiedAt == null)
                        ) prior else incoming
                    }
                    .sortedBy { it.pageIndex },
            )
            val temp = File(target.parentFile, "${target.name}.tmp")
            temp.writeText(OcrCheckpointCodec.encode(merged))
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) {
                    temp.delete()
                    error("OCR 检查点写入失败: ${target.absolutePath}")
                }
            }
        }
    }

    fun clear(documentId: String) {
        fileFor(documentId).delete()
    }

    fun fileFor(documentId: String): File =
        File(root, "${FileHashes.sha256(documentId.toByteArray(Charsets.UTF_8))}.json")

    private fun lockFor(documentId: String): Any =
        locks.computeIfAbsent(fileFor(documentId).absolutePath) { Any() }

    private companion object {
        val locks = ConcurrentHashMap<String, Any>()
    }
}
