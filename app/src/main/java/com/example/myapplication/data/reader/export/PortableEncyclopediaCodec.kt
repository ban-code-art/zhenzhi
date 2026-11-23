package com.example.myapplication.data.reader.export

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal data class PortableEncyclopediaFavorite(
    val pointCode: String,
    val createdAtUtc: Long,
)

internal data class PortableEncyclopediaState(
    val favorites: List<PortableEncyclopediaFavorite> = emptyList(),
)

/**
 * 经学百科个人状态可移植编解码（V3 可选分段 `encyclopedia-state/1.json`）。
 * 仅记录稳定穴位代码与时间戳，不包含预置图谱或教材正文。
 */
internal object PortableEncyclopediaCodec {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_FAVORITES = 2_000
    private val pointCodeRegex = Regex("[A-Z]{2,4}[0-9]{1,2}")

    fun write(value: PortableEncyclopediaState): ByteArray {
        checkState(value)
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            putJsonArray("favorites") {
                value.favorites.forEach { fav ->
                    add(
                        buildJsonObject {
                            put("pointCode", fav.pointCode)
                            put("createdAtUtc", fav.createdAtUtc)
                        },
                    )
                }
            }
        }.toString().toByteArray(Charsets.UTF_8)
        read(payload)
        return payload
    }

    fun read(bytes: ByteArray): PortableEncyclopediaState {
        val root = BackupV2Json.obj(
            BackupV2Json.parse(bytes, MAX_BYTES),
            setOf("schemaVersion", "favorites"),
        )
        v2require(
            BackupV2Json.integer(root["schemaVersion"]) == 1L,
            "UNSUPPORTED_VERSION",
            "encyclopedia state schema",
        )
        val rows = BackupV2Json.array(root["favorites"])
        v2require(rows.size <= MAX_FAVORITES, "LIMIT_EXCEEDED", "encyclopedia favorites count")
        val favorites = rows.map { el ->
            val obj = BackupV2Json.obj(el, setOf("pointCode", "createdAtUtc"))
            val code = BackupV2Json.string(obj["pointCode"])
            v2require(pointCodeRegex.matches(code), "INVALID_ENCYCLOPEDIA_STATE", "pointCode")
            val created = BackupV2Json.integer(obj["createdAtUtc"])
            v2require(created in 0..BackupV2Limits.SAFE_INTEGER, "INVALID_ENCYCLOPEDIA_STATE", "createdAtUtc")
            PortableEncyclopediaFavorite(pointCode = code, createdAtUtc = created)
        }
        val state = PortableEncyclopediaState(v2List(favorites))
        checkState(state)
        return state
    }

    private fun checkState(value: PortableEncyclopediaState) {
        v2require(value.favorites.size <= MAX_FAVORITES, "LIMIT_EXCEEDED", "encyclopedia favorites count")
        v2require(
            value.favorites.map { it.pointCode }.toSet().size == value.favorites.size,
            "DUPLICATE_ID",
            "duplicate encyclopedia favorite pointCode",
        )
    }
}
