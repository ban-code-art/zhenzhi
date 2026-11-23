package com.example.myapplication.data.knowledge.retrieval

import java.util.LinkedHashMap

/**
 * 检索缓存（设计 §7.5 / §14 / FX-10）。
 *
 * 三条铁律：
 *  1. 缓存只省 IO：miss 必须回源，任何“不在缓存就不算向量”的路径禁止；
 *  2. key 由 [CacheNamespace] 统一生成，调用点不拼字符串；
 *  3. 固定 workBudget 下 cold/warm/evicted 输出必须完全一致（缓存不参与排序）。
 *
 * 容量初值：chunk 文本 8MiB、向量 32MiB；query 向量 5min TTL。
 */
class ByteBoundedLru<K, V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long,
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

    var currentBytes: Long = 0L
        private set

    var evictionCount: Int = 0
        private set

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun put(key: K, value: V) {
        val size = sizeOf(value).coerceAtLeast(1)
        entries.remove(key)?.let { currentBytes -= sizeOf(it) }
        entries[key] = value
        currentBytes += size
        trim()
    }

    @Synchronized
    fun remove(key: K): V? {
        val removed = entries.remove(key) ?: return null
        currentBytes -= sizeOf(removed)
        return removed
    }

    /** 用户退出/内存压力：整体驱逐。 */
    @Synchronized
    fun evictAll() {
        entries.clear()
        currentBytes = 0
    }

    /** 只驱逐某个用户 namespace 的条目（登出只清该用户短时缓存）。 */
    @Synchronized
    fun evictWhere(predicate: (K) -> Boolean) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (predicate(entry.key)) {
                currentBytes -= sizeOf(entry.value)
                iterator.remove()
                evictionCount += 1
            }
        }
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun trim() {
        while (currentBytes > maxBytes && entries.isNotEmpty()) {
            val iterator = entries.entries.iterator()
            if (!iterator.hasNext()) break
            val eldest = iterator.next()
            currentBytes -= sizeOf(eldest.value)
            iterator.remove()
            evictionCount += 1
        }
    }
}

/**
 * 缓存 key 统一生成（设计 §14）。
 * 调用点禁止自行拼字符串，避免漏掉 user/profile/generation 维度。
 */
object CacheNamespace {
    fun chunkText(userId: Long, chunkId: Long, contentRevision: String, extractorVersion: String): String =
        "txt|u$userId|c$chunkId|r$contentRevision|e$extractorVersion"

    fun embedding(userId: Long, chunkId: Long, profileFingerprint: String, dim: Int, encoding: String): String =
        "vec|u$userId|c$chunkId|p$profileFingerprint|d$dim|$encoding"

    fun queryVector(userId: Long, queryHash: String, profileFingerprint: String, preprocessingVersion: String): String =
        "qv|u$userId|q$queryHash|p$profileFingerprint|pre$preprocessingVersion"

    fun lexicalCandidates(userId: Long, scopeHash: String, queryHash: String, generationHash: String): String =
        "lex|u$userId|s$scopeHash|q$queryHash|g$generationHash"

    fun userPrefix(userId: Long): String = "u$userId|"
}

/** query embedding 的 TTL 包装：过期即 miss，不返回陈旧向量。 */
class QueryVectorCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val vector: FloatArray, val createdAt: Long)

    private val lru = ByteBoundedLru<String, Entry>(maxBytes) { it.vector.size.toLong() * 4 }

    fun get(key: String): FloatArray? {
        val entry = lru.get(key) ?: return null
        if (clock() - entry.createdAt > ttlMillis) {
            lru.remove(key)
            return null
        }
        return entry.vector
    }

    fun put(key: String, vector: FloatArray) {
        lru.put(key, Entry(vector, clock()))
    }

    fun evictUser(userId: Long) {
        val prefix = CacheNamespace.userPrefix(userId)
        lru.evictWhere { it.startsWith("qv|$prefix") }
    }

    fun evictAll() = lru.evictAll()

    fun size(): Int = lru.size()

    companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        const val DEFAULT_TTL_MILLIS = 5 * 60_000L
    }
}

/** chunk 文本缓存：key 含 contentRevision/extractorVersion，源变化自然 miss。 */
class ChunkTextCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val lru = ByteBoundedLru<String, String>(maxBytes) { it.toByteArray(Charsets.UTF_8).size.toLong() }

    fun get(key: String): String? = lru.get(key)

    fun put(key: String, text: String) = lru.put(key, text)

    fun evictUser(userId: Long) {
        val prefix = CacheNamespace.userPrefix(userId)
        lru.evictWhere { it.startsWith("txt|$prefix") }
    }

    fun evictAll() = lru.evictAll()

    fun size(): Int = lru.size()

    companion object {
        const val DEFAULT_MAX_BYTES = 8L * 1024 * 1024
    }
}
