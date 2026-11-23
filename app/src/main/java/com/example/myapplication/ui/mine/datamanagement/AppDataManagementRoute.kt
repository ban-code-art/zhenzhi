package com.example.myapplication.ui.mine.datamanagement

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.ui.knowledge.backup.DataManagementEntryContext
import com.example.myapplication.ui.knowledge.backup.DataManagementInitialDocumentRequest
import com.example.myapplication.ui.knowledge.backup.DataManagementRoute
import com.example.myapplication.ui.knowledge.backup.dataManagementIdentityFromKey
import com.example.myapplication.ui.knowledge.backup.dataManagementKey
import com.example.myapplication.ui.knowledge.backup.knownDataManagementIdentity
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Both public entries resolve the same application service; leaving never cancels its job. */
@Composable
fun AppDataManagementRoute(
    onBack: () -> Unit,
    initialDocumentId: String? = null,
    initialIdentity: SessionIdentity? = null,
) {
    val application = LocalContext.current.applicationContext as LearningApplication
    val container = remember(application) { application.container }
    val port = remember(container) { container.dataManagementService }
    val currentIdentity by container.sessionManager.identity.collectAsState(initial = null)
    val documentId = initialDocumentId?.takeIf(String::isNotBlank)
    val entryId = rememberSaveable(documentId) { UUID.randomUUID().toString() }
    var originKey by rememberSaveable(documentId) {
        mutableStateOf((initialIdentity ?: knownDataManagementIdentity(port.state.value))?.dataManagementKey())
    }
    LaunchedEffect(container, entryId) {
        if (documentId != null && originKey == null) {
            // The cold service has not published an identity yet. Capture the actual session once.
            originKey = container.sessionManager.identity.first().dataManagementKey()
        }
    }
    val request = dataManagementIdentityFromKey(originKey)?.let { identity ->
        documentId?.let { DataManagementInitialDocumentRequest(entryId, it, identity) }
    }
    DataManagementRoute(
        port = port,
        entryContext = DataManagementEntryContext(currentIdentity, request),
        onBack = onBack,
    )
}
