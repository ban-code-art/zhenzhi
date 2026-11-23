package com.example.myapplication.ui.main

import com.example.myapplication.ui.mine.MineDestination
import com.example.myapplication.ui.mine.isHostedByNavHost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

internal enum class MainTabKey(val wireKey: String, val label: String) {
    KNOWLEDGE("knowledge", "知识学习"),
    TEST("test", "随机测试"),
    ENCYCLOPEDIA("encyclopedia", "经学百科"),
    MINE("mine", "我的"),
}

internal fun resolveMainTab(saved: String?, legacy: Int?): MainTabKey =
    MainTabKey.entries.firstOrNull { it.wireKey == saved } ?: when (legacy) {
        1 -> MainTabKey.TEST
        2, 3 -> MainTabKey.MINE
        else -> MainTabKey.KNOWLEDGE
    }

internal fun resolveMainTabByKey(saved: String?): MainTabKey =
    MainTabKey.entries.firstOrNull { it.wireKey == saved } ?: MainTabKey.KNOWLEDGE

internal data class MainReturnRestore(
    val tab: MainTabKey?,
    val mineStack: List<String>?,
    val dispatchLegacyDestination: MineDestination? = null,
)

private val legacySourceKeyMap = mapOf(
    "MANUAL" to "favorites",
    "WRONG" to "wrong-questions",
)

internal fun resolveMainReturnRestore(
    restored: JsonObject,
    decodeMineDestination: (String) -> MineDestination?,
): MainReturnRestore {
    val savedWireKey = (restored["mainTabKey"] as? JsonPrimitive)?.contentOrNull
    val legacyTab = (restored["mainTab"] as? JsonPrimitive)?.intOrNull
    val hasTabSignal = savedWireKey != null || legacyTab != null
    val resolvedTab = if (hasTabSignal) resolveMainTab(savedWireKey, legacyTab) else null
    val rawStack = (restored["mineStack"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

    val isLegacyRecords = savedWireKey == null && legacyTab == 2
    if (!isLegacyRecords) {
        val validStack = rawStack?.takeIf { stack -> stack.all { decodeMineDestination(it) != null } }
        return MainReturnRestore(tab = resolvedTab, mineStack = validStack)
    }

    if (rawStack != null && rawStack.isNotEmpty()) {
        val decoded = rawStack.map { decodeMineDestination(it) }
        if (decoded.any { it == null }) {
            return MainReturnRestore(tab = MainTabKey.MINE, mineStack = emptyList())
        }
        val destinations = decoded.filterNotNull()
        val last = destinations.last()
        if (last.isHostedByNavHost()) {
            return MainReturnRestore(
                tab = MainTabKey.MINE,
                mineStack = rawStack.dropLast(1),
                dispatchLegacyDestination = last,
            )
        }
        return MainReturnRestore(tab = MainTabKey.MINE, mineStack = rawStack)
    }

    val hintKey = listOf("legacyRecord", "recordPage", "recordsDestination", "destination")
        .firstNotNullOfOrNull { key -> (restored[key] as? JsonPrimitive)?.contentOrNull }
        ?: (restored["source"] as? JsonPrimitive)?.contentOrNull?.let(legacySourceKeyMap::get)
    val hintedDestination = hintKey?.let(decodeMineDestination)
    return MainReturnRestore(
        tab = MainTabKey.MINE,
        mineStack = emptyList(),
        dispatchLegacyDestination = hintedDestination,
    )
}
