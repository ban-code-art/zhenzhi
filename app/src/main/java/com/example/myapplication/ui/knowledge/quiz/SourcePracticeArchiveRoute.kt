package com.example.myapplication.ui.knowledge.quiz

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.myapplication.LearningApplication
import com.example.myapplication.data.quiz.*
import com.example.myapplication.data.reader.content.ContentPackageArchive
import com.example.myapplication.data.reader.content.ContentPackageCodec
import com.example.myapplication.data.reader.content.ContentPackageValidator
import com.example.myapplication.data.reader.content.ContentValidationMode
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.domain.reader.*
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.knowledge.course.BundledCourseCatalog
import com.example.myapplication.ui.navigation.unifiedPageBack
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*

/** Continues an actual private record, including a single saved favorite question. */
@Composable
fun SourcePracticeArchiveRoute(
    recordId: String,
    onBack: () -> Unit,
    initialQuestionId: String? = null,
    onAskAi: ((SourcePracticeScope) -> Unit)? = null,
) {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as LearningApplication).container }
    val archive = container.sourcePracticeArchiveRepository
    val identity by archive.identityFlow.collectAsState(SessionIdentity(null, -1))
    var restoration by remember(recordId, identity) { mutableStateOf<SourcePracticeArchiveRestoration?>(null) }
    var loading by remember(recordId, identity) { mutableStateOf(true) }
    var error by remember(recordId, identity) { mutableStateOf<String?>(null) }
    var retry by remember(recordId, identity) { mutableStateOf(0) }
    LaunchedEffect(recordId, identity, retry) {
        restoration = null; loading = true; error = null
        val captured = identity
        try {
            val saved = archive.load(captured, recordId) ?: throw SourcePracticeArchiveException("这份随堂记录已不存在。")
            if (archive.identityFlow.first() != captured) return@LaunchedEffect
            restoration = SourcePracticeArchiveRestoration(saved, false,
                "正在核对当前原文。题目与摘录来自已保存的练习记录。")
            loading = false
            val restored = restoreSourcePracticeArchive(archive, captured, recordId) { source ->
                val verified = withTimeoutOrNull(7_500L) { withContext(Dispatchers.IO) {
                    val ctx = AccessContext(checkNotNull(captured.userId), captured.epoch, "source-practice-archive-$recordId")
                    validateSourcePracticeArchiveDocuments(container.roomDocumentAccess, ctx, source)
                    if (source.bundledBookKey != null) validateBundledPractice(context, source)
                    currentCoroutineContext().ensureActive()
                    true
                } }
                if (verified != true) throw SourcePracticeArchiveException("原文核验超时。")
            }
            if (archive.identityFlow.first() == captured) restoration = restored
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (archive.identityFlow.first() == captured) {
                restoration = null
                error = (failure as? SourcePracticeArchiveException)?.message
                    ?: (failure as? SourcePracticeLoadException)?.message ?: "这份练习的原文暂不可用，请返回阅读后重试。"
            }
        } finally { if (archive.identityFlow.first() == captured) loading = false }
    }
    val restored = restoration
    val loaded = restored?.record
    if (restored != null && loaded != null) {
        TrackedSourcePracticeScreen(loaded.practice, loaded.scope, onBack,
            onAskAi = onAskAi.takeIf { restored.currentSourceAvailable }, repository = archive, batchNumber = loaded.batchNumber,
            recordId = loaded.id, initialQuestionId = initialQuestionId, sourceWarning = restored.sourceWarning)
    } else {
        val requestBack = unifiedPageBack(onBack)
        Box(Modifier.fillMaxSize().testTag("source-practice-archive-route"), contentAlignment = Alignment.Center) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (loading) { CircularProgressIndicator(); Text("正在核对并恢复随堂记录…") }
                else {
                    Text(error ?: "当前没有可恢复的记录。", modifier = Modifier.testTag("source-practice-archive-error"))
                    UiAction(label = "重试", onClick = { retry++ }, filled = true,
                        modifier = Modifier.testTag("source-practice-archive-retry"), actionId = "sourcePractice.archive.retry")
                }
                UiAction(label = "返回", onClick = requestBack, actionId = "sourcePractice.archive.back")
            }
        }
    }
}

internal suspend fun validateSourcePracticeArchiveDocuments(access: DocumentAccess, ctx: AccessContext, scope: SourcePracticeScope) {
    val bindings = scope.relatedSources.toMutableList()
    scope.documentId?.let { document ->
        if (bindings.none { it.documentId == document }) bindings += SourcePracticeDocumentBinding(document, scope.sourceRevision,
            scope.sectionIds, scope.documentOwnerUserId, scope.accessEpoch, scope.metadataRevision, scope.extractorVersion)
    }
    val loader = SourcePracticeLoader(access)
    for (binding in bindings) {
        currentCoroutineContext().ensureActive()
        val snapshot = loader.open(ctx, binding.documentId, binding.sourceRevision)
        if (snapshot.documentId != binding.documentId || snapshot.contentRevision != binding.sourceRevision ||
            snapshot.ownerUserId != binding.ownerUserId ||
            (binding.accessEpoch != null && snapshot.accessEpoch != binding.accessEpoch) ||
            (binding.metadataRevision != null && snapshot.metadataRevision != binding.metadataRevision) ||
            (binding.extractorVersion != null && snapshot.extractorVersion != binding.extractorVersion))
            throw SourcePracticeArchiveException("这份练习的原文权限或版本已变化，请返回阅读重新生成。")
        val wanted = binding.sectionIds.toSet()
        val sections = snapshot.sections.map { it.id }.toMutableSet()
        var cursor = snapshot.sectionsNextCursor
        val seen = mutableSetOf<String>()
        while (!sections.containsAll(wanted) && cursor != null) {
            currentCoroutineContext().ensureActive()
            if (seen.size >= 128 || !seen.add(cursor) || sections.size > 1024)
                throw SourcePracticeArchiveException("原文章节暂时无法核对，请返回阅读。")
            val page = (access.readSectionDescriptors(ctx, snapshot.snapshotId, cursor, 64) as? OperationResult.Ok)?.value
                ?: throw SourcePracticeArchiveException("原文章节已变化，请返回阅读重新选择。")
            if (page.items.any { it.documentId != snapshot.documentId || it.contentRevision != snapshot.contentRevision })
                throw SourcePracticeArchiveException("原文章节版本已变化，请返回阅读重新生成。")
            sections += page.items.map { it.id }
            cursor = page.nextCursor
        }
        if (!sections.containsAll(wanted)) throw SourcePracticeArchiveException("练习对应的章节已不可用，请返回阅读。")
        loader.verifySnapshot(ctx, snapshot)
    }
}

private suspend fun validateBundledPractice(context: Context, scope: SourcePracticeScope) {
    val output = ByteArrayOutputStream()
    context.assets.open(BundledCourseCatalog.ASSET_PATH).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size().toLong() + count > 32L * 1024 * 1024)
                throw SourcePracticeArchiveException("课程材料过大，暂时无法恢复这份练习。")
            output.write(buffer, 0, count)
        }
    }
    val archive = ContentPackageArchive.read(output.toByteArray())
    if (ContentPackageValidator().validate(archive.manifestBytes, archive.resources, ContentValidationMode.CANONICAL).canonicalValid != true)
        throw SourcePracticeArchiveException("内置课程内容校验失败，暂时无法恢复练习。")
    val root = ContentPackageCodec.decode(archive.manifestBytes)
    val book = root.getValue("books").jsonArray.map { it.jsonObject }.singleOrNull {
        it.getValue("bookKey").jsonPrimitive.content == scope.bundledBookKey
    } ?: throw SourcePracticeArchiveException("这份练习对应的内置教材已不可用。")
    if (book.getValue("chapterKeys").jsonArray.none { it.jsonPrimitive.content == scope.bundledChapterKey })
        throw SourcePracticeArchiveException("这份练习的教材章节已变化。")
    val chapter = root.getValue("chapters").jsonArray.map { it.jsonObject }.singleOrNull {
        it.getValue("chapterKey").jsonPrimitive.content == scope.bundledChapterKey
    } ?: throw SourcePracticeArchiveException("这份练习对应的内置章节已不可用。")
    val bodyPath = chapter.getValue("bodyPath").jsonPrimitive.content
    val body = archive.resources[bodyPath] ?: throw SourcePracticeArchiveException("这份练习的课程正文已不可用。")
    val sha = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
    if (sha != scope.sourceRevision) throw SourcePracticeArchiveException("课程正文已更新，请重新生成本章练习。")
}
