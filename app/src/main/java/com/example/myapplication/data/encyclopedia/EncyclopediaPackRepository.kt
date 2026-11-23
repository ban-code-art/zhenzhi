package com.example.myapplication.data.encyclopedia

import android.content.res.AssetManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface EncyclopediaLoadState {
    data object Idle : EncyclopediaLoadState
    data object Loading : EncyclopediaLoadState
    data class Ready(val snapshot: EncyclopediaSnapshot) : EncyclopediaLoadState
    data class Error(
        val code: EncyclopediaPackErrorCode,
        val message: String,
        val cause: Throwable? = null,
    ) : EncyclopediaLoadState
}

/**
 * 经学百科只读资料仓库。
 * 在 I/O 调度器完成全量解析与完整性校验后一次性原子发布有效快照；校验失败时暴露可读错误状态并支持 [reload] 重试。
 */
class EncyclopediaPackRepository(
    private val source: EncyclopediaPackSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val loader: EncyclopediaPackLoader = EncyclopediaPackLoader,
) {
    constructor(
        assetManager: AssetManager,
        assetRoot: String = EncyclopediaPackConstants.ASSET_ROOT,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        source = AssetEncyclopediaPackSource(assetManager = assetManager, rootPath = assetRoot),
        ioDispatcher = ioDispatcher,
    )

    private val loadMutex = Mutex()
    private val mutableState = MutableStateFlow<EncyclopediaLoadState>(EncyclopediaLoadState.Idle)
    val state: StateFlow<EncyclopediaLoadState> = mutableState.asStateFlow()

    val currentSnapshot: EncyclopediaSnapshot?
        get() = (mutableState.value as? EncyclopediaLoadState.Ready)?.snapshot

    suspend fun loadIfNeeded(): EncyclopediaLoadState {
        val current = mutableState.value
        if (current is EncyclopediaLoadState.Ready) {
            return current
        }
        return loadMutex.withLock {
            val lockedCurrent = mutableState.value
            if (lockedCurrent is EncyclopediaLoadState.Ready) {
                lockedCurrent
            } else {
                executeLoadLocked()
            }
        }
    }

    suspend fun reload(): EncyclopediaLoadState =
        loadMutex.withLock {
            executeLoadLocked()
        }

    private suspend fun executeLoadLocked(): EncyclopediaLoadState {
        if (mutableState.value !is EncyclopediaLoadState.Ready) {
            mutableState.value = EncyclopediaLoadState.Loading
        }
        val nextState = try {
            val snapshot = withContext(ioDispatcher) {
                loader.load(source)
            }
            EncyclopediaLoadState.Ready(snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (packError: EncyclopediaPackException) {
            EncyclopediaLoadState.Error(
                code = packError.code,
                message = packError.message ?: "经学百科资料包校验失败",
                cause = packError,
            )
        } catch (unexpected: Throwable) {
            EncyclopediaLoadState.Error(
                code = EncyclopediaPackErrorCode.CORRUPTED_JSON,
                message = unexpected.message ?: "经学百科资料包读取失败",
                cause = unexpected,
            )
        }
        mutableState.value = nextState
        return nextState
    }
}
