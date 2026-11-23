package com.example.myapplication.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.myapplication.AppContainer
import com.example.myapplication.data.agent.evidence.SourceNavigator
import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceVerifier
import com.example.myapplication.data.encyclopedia.EncyclopediaLoadState
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.data.encyclopedia.EncyclopediaVerificationResult
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.ui.ai.AgentLaunchContext
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

private data class LoadedReturn(
    val loading: Boolean = true,
    val point: ReturnPoint? = null,
    val launch: AgentLaunchContext? = null,
    val error: String? = null,
)

/** A missing/unauthorized key never falls through to the legacy route parameters. */
@Composable
internal fun ReturnDestination(
    key: String,
    sourceReader: Boolean,
    store: ReturnPointStore,
    sessions: SessionManager,
    container: AppContainer,
    onBack: (ReturnPoint?) -> Unit,
    content: @Composable (ReturnPoint, AgentLaunchContext) -> Unit,
) {
    val loaded by produceState(LoadedReturn(), key, sourceReader) {
        sessions.identity.collectLatest { identity ->
            value = LoadedReturn()
            var point: ReturnPoint? = null
            var launch: AgentLaunchContext? = null
            try {
                val userId = identity.userId ?: error("登录状态已失效，请返回后重新登录")
                point = store.get(userId, key) ?: error("返回现场已失效，请返回原页面重新打开")
                val payload = store.payload(userId, key) ?: error("返回内容缺失，请重新打开")
                launch = withContext(Dispatchers.Default) { ReturnPayloadCodec.decode(payload) }
                if (sourceReader) {
                    val ctx = AccessContext(userId, identity.epoch, "return-$key")
                    val referenceId = withContext(Dispatchers.Default) { ReturnPayloadCodec.referenceId(payload) }
                    if (!referenceId.isNullOrBlank()) {
                        when (val result = container.sourceNavigator.open(ctx, referenceId)) {
                            is SourceNavigator.Outcome.Verified -> {
                                require(result.documentId == point.documentId && result.contentRevision == point.revision) {
                                    "来源版本已变化，无法精确跳转"
                                }
                            }
                            is SourceNavigator.Outcome.EncyclopediaVerified -> {
                                val expected = EncyclopediaSourceLocator.decode(point.locatorJson ?: launch?.locatorJson)
                                require(
                                    expected != null &&
                                        result.locator.entryId == expected.entryId &&
                                        result.locator.contentRevision == expected.contentRevision,
                                ) {
                                    "百科词条版本已变化，无法精确跳转"
                                }
                            }
                            is SourceNavigator.Outcome.Missing -> error("来源已失效：${result.reason}")
                            is SourceNavigator.Outcome.Revoked -> error("来源不可访问：${result.reason}")
                        }
                    } else {
                        val encLoc = EncyclopediaSourceLocator.decode(point.locatorJson ?: launch?.locatorJson)
                        if (encLoc != null) {
                            val snap = container.encyclopediaPackRepository.currentSnapshot
                                ?: (container.encyclopediaPackRepository.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot
                            val checked = EncyclopediaEvidenceVerifier.verify(
                                snapshot = snap,
                                locator = encLoc,
                                expectedRevision = encLoc.contentRevision,
                                expectedQuote = launch?.quote.orEmpty(),
                            )
                            require(checked is EncyclopediaVerificationResult.Verified) {
                                "百科来源版本或正文已变化，无法精确跳转"
                            }
                        } else {
                            val documentId = point.documentId ?: error("来源文档缺失")
                            require(!point.revision.isNullOrBlank()) { "来源版本缺失，无法精确跳转" }
                            require(container.roomDocumentAccess.open(ctx, documentId, point.revision) is OperationResult.Ok) {
                                "来源已删除、撤权或版本变化，无法精确跳转"
                            }
                        }
                    }
                }
                // Do not publish payload read under an identity that changed during source verification.
                if (store.get(userId, key) == null) error("登录会话已变化，请重新打开")
                value = LoadedReturn(false, point, launch)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val stillAuthorized = sessions.identity.first() == identity
                value = LoadedReturn(false, point.takeIf { stillAuthorized }, launch.takeIf { stillAuthorized },
                    failure.message ?: "无法恢复返回现场")
            }
        }
    }
    when {
        loaded.loading -> Box(
            Modifier.fillMaxSize().background(UiRoles.surface(UiSurfaceRole.Page)).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    "正在恢复现场…",
                    style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                    color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                )
            }
        }
        loaded.error != null -> {
            val requestBack = unifiedPageBack { onBack(loaded.point) }
            SubPageScaffold(
                title = "来源恢复",
                onBack = requestBack,
                glassBack = true,
                actionId = "returnDestination.back",
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, top = GlassPageBarHeight + 12.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        loaded.error.orEmpty(),
                        style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                        color = UiRoles.foreground,
                    )
                    loaded.launch?.quote?.let { quote ->
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = UiRoles.surface(UiSurfaceRole.Card),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                "历史摘录\n$quote",
                                modifier = Modifier.padding(16.dp),
                                style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                                color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                            )
                        }
                    }
                    UiAction(
                        label = "返回原页面",
                        onClick = requestBack,
                        filled = true,
                        actionId = "returnDestination.returnToCaller",
                    )
                }
            }
        }
        else -> content(requireNotNull(loaded.point), requireNotNull(loaded.launch))
    }
}
