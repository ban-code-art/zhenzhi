package com.example.myapplication.ui.knowledge.reader

import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.domain.reader.ReaderLocator

data class HostedReflowSurface(val id: String, val surface: ReflowReaderSurface) {
    internal var selectionPort: ReflowSelectionSurfacePort? = null
}

/** Staged views are attached at full size but hidden until a committed session is revealed. */
interface ReflowRuntimeHost {
    suspend fun createSurface(): HostedReflowSurface
    suspend fun showSurface(id: String)
    suspend fun releaseSurface(id: String)
    suspend fun hideAll()
}

interface ReflowProgressSink {
    suspend fun commit(locator: ReaderLocator): OperationResult<Unit>
    suspend fun close()
}

data class ReflowReaderRuntime(val controller: ReaderRuntimeController, val readOnly: Boolean)
