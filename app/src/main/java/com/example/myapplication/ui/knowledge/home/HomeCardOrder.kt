package com.example.myapplication.ui.knowledge.home

internal fun reconcileHomeOrder(previous: List<String>, incoming: List<String>): List<String> {
    val visibleKeys = incoming.toHashSet()
    return (previous.filter(visibleKeys::contains) + incoming).distinct()
}

internal fun moveHomeCard(order: List<String>, source: String, target: String): List<String> {
    val sourceIndex = order.indexOf(source)
    val targetIndex = order.indexOf(target)
    if (sourceIndex < 0 || targetIndex < 0 || sourceIndex == targetIndex) return order

    return order.toMutableList().apply {
        add(targetIndex, removeAt(sourceIndex))
    }
}
