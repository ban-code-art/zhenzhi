package com.example.myapplication.ui.knowledge.review

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.review.ReviewCandidate
import com.example.myapplication.data.review.ReviewIdentityExpiredException
import com.example.myapplication.data.review.ReviewSourceCatalog
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.navigation.LocalNavigationPageActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ReviewQuickAddAction(
    sourceKey: Any,
    tag: String,
    enabled: Boolean = true,
    expectedIdentity: SessionIdentity? = null,
    inheritedPane: Boolean = false,
    load: suspend ReviewSourceCatalog.(SessionIdentity) -> ReviewCandidate?,
) {
    val app = LocalContext.current.applicationContext as? LearningApplication ?: return
    val container = app.container
    val identity by container.sessionManager.identity.collectAsState(initial = null)
    val account = identity?.takeIf { it.userId != null && (expectedIdentity == null || expectedIdentity == it) }
    val scope = rememberCoroutineScope()
    val pageActive = LocalNavigationPageActive.current
    var saving by remember(sourceKey, account) { mutableStateOf(false) }
    var added by remember(sourceKey, account) { mutableStateOf(false) }
    var error by remember(sourceKey, account) { mutableStateOf<String?>(null) }
    val readyAccount = account?.takeIf { enabled && !saving && !added }

    UiAction(
        label = if (added) "已加入回顾" else "加入回顾",
        enabled = pageActive && readyAccount != null,
        keepEnabledStyle = readyAccount != null,
        busy = saving,
        inheritedPane = inheritedPane,
        modifier = Modifier.testTag(tag),
        actionId = "review.quickAdd",
        onClick = {
            if (pageActive && readyAccount != null && !saving && !added) {
                saving = true
                error = null
                scope.launch {
                    try {
                        val candidate = container.reviewSources.load(readyAccount)
                        added = candidate != null &&
                            container.reviewRepository.addItems(readyAccount, listOf(candidate)).isNotEmpty()
                        if (!added) error = "没有可用的题目和答案。请确认来源仍可读，或选择包含完整知识点的内容。"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: ReviewIdentityExpiredException) {
                        error = "登录状态已变化，请重新打开来源。"
                    } catch (_: Exception) {
                        error = "暂时无法加入回顾，请重试。"
                    } finally {
                        saving = false
                    }
                }
            }
        },
    )
    error?.let { message ->
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}
