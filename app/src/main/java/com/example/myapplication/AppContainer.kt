package com.example.myapplication

import kotlinx.coroutines.flow.first

import android.content.Context
import com.example.myapplication.data.ai.config.CapabilityProbe
import com.example.myapplication.data.update.UpdateInstaller
import com.example.myapplication.data.update.createAppUpdateCheck
import com.example.myapplication.data.ai.config.ProfileRepository
import com.example.myapplication.data.ai.config.ProfileRuntime
import com.example.myapplication.data.ai.config.SecretStore
import com.example.myapplication.data.ai.net.KtorTransport
import com.example.myapplication.data.agent.ContainerAgentRuntimeFactory
import com.example.myapplication.data.agent.ConversationRepository
import com.example.myapplication.data.agent.RunCoordinator
import com.example.myapplication.data.agent.notes.NoteProposalService
import com.example.myapplication.data.agent.evidence.PersistentSourceReferencePort
import com.example.myapplication.data.agent.evidence.SourceNavigator
import com.example.myapplication.data.agent.evidence.SourceVerifier
import com.example.myapplication.data.quiz.ChatQuizDraftSource
import com.example.myapplication.data.quiz.GradeAssessmentService
import com.example.myapplication.data.quiz.QuizGenerationCoordinator
import com.example.myapplication.data.encyclopedia.EncyclopediaPackRepository
import com.example.myapplication.data.quiz.QuizRepository
import com.example.myapplication.data.quiz.AiReviewAdoption
import com.example.myapplication.data.quiz.QuizAiReviewService
import com.example.myapplication.data.quiz.QuizSubmissionService
import com.example.myapplication.data.knowledge.KnowledgeStats
import com.example.myapplication.data.knowledge.RoomDocumentAccess
import com.example.myapplication.data.knowledge.index.IndexLease
import com.example.myapplication.data.knowledge.index.IndexOutbox
import com.example.myapplication.data.knowledge.index.IndexProcessor
import com.example.myapplication.data.knowledge.index.IndexPublisher
import com.example.myapplication.data.knowledge.index.IndexRunner
import com.example.myapplication.data.knowledge.index.VectorIndexer
import com.example.myapplication.data.knowledge.index.VectorEncodingPreference
import com.example.myapplication.data.knowledge.index.DataStoreVectorEncodingPreference
import com.example.myapplication.data.knowledge.lexical.LexicalIndex
import com.example.myapplication.data.knowledge.retrieval.AnnManifestStore
import com.example.myapplication.data.knowledge.retrieval.AnnVectorBackend
import com.example.myapplication.data.knowledge.retrieval.EvidenceAssembler
import com.example.myapplication.data.knowledge.retrieval.ExactVectorBackend
import com.example.myapplication.data.knowledge.retrieval.HybridRetriever
import com.example.myapplication.data.knowledge.retrieval.QueryEmbeddingService
import com.example.myapplication.data.knowledge.retrieval.RetrievalScopeResolver
import com.example.myapplication.data.local.AppDatabase
import com.example.myapplication.data.reader.importer.ImportCoordinator
import com.example.myapplication.data.reader.importer.ImportPublisher
import com.example.myapplication.data.reader.importer.ImportRecovery
import com.example.myapplication.data.reader.importer.ImportJobStore
import com.example.myapplication.data.reader.importer.RoomImportJobStore
import com.example.myapplication.data.reader.ReaderDocumentRepository
import com.example.myapplication.data.reader.export.BackupArchive
import com.example.myapplication.data.reader.export.BackupPackageWriter
import com.example.myapplication.data.reader.export.RestoreCoordinator
import com.example.myapplication.data.reader.export.BackupV2Service
import com.example.myapplication.data.reader.export.RoomBackupV2ServiceBackend
import com.example.myapplication.data.reader.export.BackupV2RoomStore
import com.example.myapplication.data.reader.export.BackupV2RevisionSnapshot
import com.example.myapplication.data.reader.export.BackupV2PackageWriter
import com.example.myapplication.data.reader.export.BackupV2PublicationVerifier
import com.example.myapplication.data.reader.export.BackupV2ProofVerifier
import com.example.myapplication.data.reader.export.AndroidBackupV2CourseVerifier
import com.example.myapplication.data.reader.export.BackupV2VerifiedSupport
import com.example.myapplication.data.reader.export.BackupV2RestoreCoordinator
import com.example.myapplication.data.reader.export.RestoreJournalV2
import com.example.myapplication.data.reader.ink.InkStore
import com.example.myapplication.data.ocr.MlKitOcrEngine
import com.example.myapplication.data.ocr.OcrCheckpointStore
import com.example.myapplication.data.ocr.OcrDocumentProcessor
import com.example.myapplication.data.ocr.OcrRevisionPublisher
import com.example.myapplication.data.ocr.OcrScheduler
import com.example.myapplication.data.ocr.OcrWorker
import com.example.myapplication.data.ocr.OcrReviewService
import com.example.myapplication.data.search.DataStoreEnhancementToggles
import com.example.myapplication.data.search.EnhancementToggles
import com.example.myapplication.data.search.ExternalSearchPort
import com.example.myapplication.data.search.ExternalSearchSettings
import com.example.myapplication.data.search.DataStoreExternalSearchSettings
import com.example.myapplication.data.search.SearxngExternalSearchPort
import com.example.myapplication.data.reader.notes.AnnotationRepository
import com.example.myapplication.data.reader.notes.NoteRepository
import com.example.myapplication.data.reader.notes.SourceReferenceStore
import com.example.myapplication.data.reader.BuiltInContentSeeder
import com.example.myapplication.data.reader.storage.DocumentStorage
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.ocr.OcrEngine
import com.example.myapplication.domain.reader.OperationResult
import com.example.myapplication.ui.knowledge.reader.legacyReaderText
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

class AppContainer(context: Context, private val backupV2VerifiedSupport: BackupV2VerifiedSupport = BackupV2VerifiedSupport(
    BackupV2VerifiedSupport.BUNDLE + com.example.myapplication.data.reader.export.DataManagementContent.SOURCES
)) {
    private val applicationContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.getInstance(applicationContext) }
    val sessionManager: SessionManager by lazy { SessionManager(applicationContext) }
    val reviewRepository by lazy {
        com.example.myapplication.data.review.ReviewRepository(
            database, sessionManager, sourceStatus = reviewSources::statusWithinIdentity, legacySources = reviewSources,
        )
    }
    val encyclopediaUserRepository: com.example.myapplication.data.encyclopedia.EncyclopediaUserRepository by lazy {
        com.example.myapplication.data.encyclopedia.EncyclopediaUserRepository(
            database = database,
            sessions = sessionManager,
            reviewRepository = reviewRepository,
            packRepository = encyclopediaPackRepository,
        )
    }
    val reviewSources by lazy {
        com.example.myapplication.data.review.ReviewSourceCatalog(database, sessionManager, sourcePracticeArchiveRepository, readReflowText = { user, locator ->
            val session = (readerRepository.openSession(user, locator.documentId, locator.contentRevision) as? OperationResult.Ok)
                ?.value ?: return@ReviewSourceCatalog null
            val section = session.sections.firstOrNull { it.id == locator.sectionId } ?: return@ReviewSourceCatalog null
            if (section.charCount > 320_000) return@ReviewSourceCatalog null
            withContext(Dispatchers.IO) {
                val content = (readerRepository.loadSection(session, section.id) as? OperationResult.Ok)?.value
                content?.html?.takeIf { it.length <= 1_200_000 }?.let(::legacyReaderText)
            }
        }, encyclopedia = encyclopediaPackRepository)
    }
    val learningActivityRecorder: com.example.myapplication.data.learning.LearningActivityRecorder by lazy {
        com.example.myapplication.data.learning.LearningActivityRecorder(database.learningActivityDao(),
            identityNow = { sessionManager.identity.first() })
    }
    val sourcePracticeArchiveRepository: com.example.myapplication.data.quiz.SourcePracticeArchiveRepository by lazy {
        com.example.myapplication.data.quiz.SourcePracticeArchiveRepository(applicationContext.filesDir,
            sessionManager.identity, onQuizActivity = learningActivityRecorder::recordQuiz)
    }
    val chapterQuestionAnswerService: com.example.myapplication.data.learning.ChapterQuestionAnswerService by lazy {
        com.example.myapplication.data.learning.ChapterQuestionAnswerService.fromContainer(database,
            profileRepository, profileRuntime, ktorTransport)
    }
    val documentStorage: DocumentStorage by lazy {
        DocumentStorage(File(applicationContext.filesDir, "reader"))
    }
    /** Explicit schema2 publication API; existing assets stay active until a verified Room transaction. */
    val contentPackageVersionStore: com.example.myapplication.data.reader.content.ContentPackageVersionStore by lazy {
        com.example.myapplication.data.reader.content.ContentPackageVersionStore(File(applicationContext.filesDir.canonicalFile, "content-packages"))
    }
    val contentPackagePublisher: com.example.myapplication.data.reader.content.ContentPackageRoomPublisher by lazy {
        com.example.myapplication.data.reader.content.ContentPackageRoomPublisher(database, documentStorage)
    }
    /** Explicit authorities are supplied by the caller; default application services stay unapproved. */
    suspend fun prepareContentQuizRuntime(
        chapterKey: String,
        professionalVerifier: com.example.myapplication.data.reader.content.ContentProfessionalReviewVerifier? = null,
        finalReviews: Map<String, com.example.myapplication.data.reader.content.ContentQuizFinalReview> = emptyMap(),
        finalReviewVerifier: com.example.myapplication.data.reader.content.ContentQuizFinalReviewVerifier? = null,
    ): com.example.myapplication.data.reader.content.ContentQuizRuntimeResult =
        com.example.myapplication.data.reader.content.ContentPackageQuizRuntime.forRoom(database, contentPackageVersionStore, documentStorage)
            .load(chapterKey, professionalVerifier, finalReviews, finalReviewVerifier)

    data class ContentQuizServices(
        val coordinator: QuizGenerationCoordinator,
        val reviewService: com.example.myapplication.data.quiz.QuizReviewService,
    )

    fun createContentQuizServices(snapshot: com.example.myapplication.data.reader.content.ContentPackageQuizRuntime.Snapshot): ContentQuizServices {
        val access = snapshot.guard(roomDocumentAccess)
        return ContentQuizServices(
            QuizGenerationCoordinator(database, conversationRepository, access, database.readerCatalogDao(), applicationScope,
                draftSource = chatQuizDraftSource,
                reviewDraftStore = com.example.myapplication.data.quiz.QuizReviewDraftStore(applicationContext.filesDir),
                supportAssessor = snapshot.supportAssessor, auditedFactProvider = snapshot::factForItem),
            com.example.myapplication.data.quiz.QuizReviewService(database,
                com.example.myapplication.data.quiz.QuizReviewDraftStore(applicationContext.filesDir), access,
                identityProvider = { sessionManager.identity.first() }, supportAssessor = snapshot.supportAssessor,
                auditedFactProvider = snapshot::factByKey),
        )
    }
    val importJobStore: ImportJobStore by lazy {
        RoomImportJobStore(database.readerJobDao())
    }
    val importCoordinator: ImportCoordinator by lazy {
        ImportCoordinator(documentStorage, importJobStore)
    }
    val importPublisher: ImportPublisher by lazy {
        ImportPublisher(documentStorage, database)
    }
    val markdownReparser: com.example.myapplication.data.reader.importer.MarkdownReparser by lazy {
        com.example.myapplication.data.reader.importer.MarkdownReparser(database, documentStorage, sessionManager)
    }
    val readerRepository: ReaderDocumentRepository by lazy {
        ReaderDocumentRepository(database, documentStorage, applicationContext.assets)
    }
    /** Explicit metadata provider; does not enable the unqualified course reading runtime. */
    val courseReadingSequenceProvider: com.example.myapplication.data.reader.RoomCourseReadingSequenceProvider by lazy {
        com.example.myapplication.data.reader.RoomCourseReadingSequenceProvider(database, readerRepository) {
            sessionManager.identity.first()
        }
    }
    val annotationRepository: AnnotationRepository by lazy { AnnotationRepository(database) }
    val noteRepository: NoteRepository by lazy { NoteRepository(database) }
    val sourceReferenceStore: SourceReferenceStore by lazy { SourceReferenceStore(annotationRepository) }
    /**
     * 墨迹存储（二期 P1-14）：这里只持有**共享根**，绝不固定当前用户。
     * 真实读写目录按 userId 解析为 `reader/ink/u<userId>/...`，所有调用点
     * （load/save/delete）必须传入当前 userId，缺用户时拒绝而不是回落到共享目录。
     */
    val inkStore: InkStore by lazy { InkStore(File(applicationContext.filesDir, "reader/ink")) }
    val importRecovery: ImportRecovery by lazy {
        ImportRecovery(applicationContext, database)
    }
    val builtInContentSeeder: BuiltInContentSeeder by lazy {
        BuiltInContentSeeder(database, applicationContext.assets)
    }

    // ---- 第三期：AI 配置与 RAG 基座 ----
    /** 统一 HTTP 传输层：连接/首事件/空闲/总请求超时，取消即断。 */
    val ktorTransport: KtorTransport by lazy { KtorTransport() }
    /** Keystore AES-GCM 密钥密文容器；Room 只存 secret_ref。 */
    val secretStore: SecretStore by lazy { SecretStore(applicationContext) }
    /** 配置仓库：草稿/测试与保存分离，CAS 保存 + secret 引用切换。 */
    val profileRepository: ProfileRepository by lazy {
        ProfileRepository(database.aiProfileDao(), secretStore)
    }
    /** 能力探测：用实际业务请求验证，不用名称猜。 */
    val capabilityProbe: CapabilityProbe by lazy { CapabilityProbe(ktorTransport) }
    /** 运行期授权门禁与 secret 引用收敛（FX-24）。 */
    val profileRuntime: ProfileRuntime by lazy {
        ProfileRuntime(database.aiProfileDao(), database.aiRunDao(), secretStore, profileRepository)
    }
    /** 向量编码偏好保存在设备 DataStore，FLOAT32 默认，不进入备份包。 */
    val vectorEncodingPreference: VectorEncodingPreference by lazy {
        DataStoreVectorEncodingPreference(applicationContext)
    }

    // ---- 第三期：知识库索引与检索（W3-06 ~ W3-11） ----
    /** DocumentAccess 的 Room 实现：固定 revision 读取 + 每次复核授权。 */
    val roomDocumentAccess: RoomDocumentAccess by lazy {
        RoomDocumentAccess(database, documentStorage, applicationContext.assets)
    }
    /** 索引任务 outbox：登记与入队同事务，幂等键去重。 */
    val indexOutbox: IndexOutbox by lazy { IndexOutbox(database) }
    /** CAS 租约：Worker 与前台共享同一 claim 服务。 */
    val indexLease: IndexLease by lazy { IndexLease(database.knowledgeJobDao()) }
    /** LOCAL 发布：staging 代次完成后一次事务切 active。 */
    val indexPublisher: IndexPublisher by lazy {
        IndexPublisher(database, roomDocumentAccess, indexLease)
    }
    /** VECTOR 增强：批 16、每批复核授权/租约/源/代次。 */
    val vectorIndexer: VectorIndexer by lazy {
        VectorIndexer(
            database = database,
            lease = indexLease,
            profileRuntime = profileRuntime,
            profileRepository = profileRepository,
            transport = ktorTransport,
        )
    }
    /** 索引编排：LOCAL/VECTOR 到期消费 + 启动恢复。 */
    val indexProcessor: IndexProcessor by lazy {
        IndexProcessor(database, indexPublisher, vectorIndexer)
    }
    /** 容器级运行入口：解析当前 embedding profile + 补齐 VECTOR 任务。 */
    val indexRunner: IndexRunner by lazy {
        IndexRunner(database, indexProcessor, vectorEncodingPreference = vectorEncodingPreference)
    }

    /** 词法索引：中文双字/术语 FTS4，本地搜索不读密钥、不要求 AI enabled。 */
    val lexicalIndex: LexicalIndex by lazy {
        LexicalIndex(database.knowledgeChunkDao(), database.knowledgeLexicalDao())
    }
    /** exact 向量后端：固定 pin 分批扫描 + 字节预算缓存。 */
    val exactVectorBackend: ExactVectorBackend by lazy {
        ExactVectorBackend(database.knowledgeEmbeddingDao())
    }
    /** ANN manifest 文件存储（可重建派生物；损坏即删回退 exact）。 */
    val annManifestStore: AnnManifestStore by lazy {
        AnnManifestStore(File(applicationContext.filesDir, "reader/ann"))
    }
    /** ANN 后端：base + delta overlay；不可用回退 exact 并标降级。 */
    val annVectorBackend: AnnVectorBackend by lazy {
        AnnVectorBackend(database.knowledgeEmbeddingDao(), annManifestStore, exactVectorBackend)
    }
    /** 证据装配：DocumentAccess 固定 revision 逐字核验。 */
    val evidenceAssembler: EvidenceAssembler by lazy {
        EvidenceAssembler(roomDocumentAccess)
    }
    /** 查询向量：5min TTL 缓存，未授权返回 null（降级词法）。 */
    val queryEmbeddingService: QueryEmbeddingService by lazy {
        QueryEmbeddingService(database, profileRuntime, profileRepository, ktorTransport)
    }
    /** 混合检索：词法 80 + 向量 80 → RRF → MMR → 证据核验。 */
    val hybridRetriever: HybridRetriever by lazy {
        HybridRetriever(
            lexicalIndex = lexicalIndex,
            exactBackend = exactVectorBackend,
            annBackend = annVectorBackend,
            evidenceAssembler = evidenceAssembler,
            documentAccess = roomDocumentAccess,
            queryEmbedding = queryEmbeddingService,
            vectorEncodingPreference = vectorEncodingPreference,
            scopePinVerifier = HybridRetriever.RoomScopePinVerifier(database.knowledgeDocumentDao()),
        )
    }
    /** scope 解析与 pin（授权/代次/源 revision 复核）。 */
    val retrievalScopeResolver: RetrievalScopeResolver by lazy {
        RetrievalScopeResolver(database)
    }
    /** 统计页 SQL 聚合。 */
    val knowledgeStats: KnowledgeStats by lazy { KnowledgeStats(database) }

    // ---- 第四期：Agent Runtime 与会话（W4-04） ----
    /** 会话/消息持久（typed message_json，最近 50 + 向上分页）。 */
    val conversationRepository: ConversationRepository by lazy { ConversationRepository(database) }

    /** 每轮运行的容器级组装：scope pin + CHAT profile/secret + 工具注册表。 */
    val agentRuntimeFactory: ContainerAgentRuntimeFactory by lazy {
        ContainerAgentRuntimeFactory(
            database = database,
            conversations = conversationRepository,
            profileRepository = profileRepository,
            profileRuntime = profileRuntime,
            scopeResolver = retrievalScopeResolver,
            documentAccess = roomDocumentAccess,
            retriever = hybridRetriever,
            noteRepository = noteRepository,
            noteProposalPort = noteProposalService,
            transport = ktorTransport,
            quizGenerationCoordinator = quizGenerationCoordinator,
            enhancementToggles = enhancementToggles,
            externalSearchPort = externalSearchPort,
            encyclopediaPackRepository = encyclopediaPackRepository,
        )
    }

    /** 运行编排：应用 scope 持有 run，UI 只订阅 runId。 */
    val runCoordinator: RunCoordinator by lazy {
        RunCoordinator(
            database = database,
            conversations = conversationRepository,
            runtimeFactory = agentRuntimeFactory,
            scope = applicationScope,
        )
    }

    // ---- 第四期：UI 只读入口（W4-11 接线） ----
    /** 普通40题测试保持独立记录与评分，不使用随堂测paper/attempt。 */
    val ordinaryTestService by lazy {
        com.example.myapplication.data.test.OrdinaryTestService(database, applicationContext)
    }
    /** 随堂测试卷/作答只读仓库。 */
    val quizRepository: QuizRepository by lazy { QuizRepository(database) }
    /** 经学百科只读离线资料包仓库（14经、361穴、45病症）。 */
    val encyclopediaPackRepository: EncyclopediaPackRepository by lazy {
        EncyclopediaPackRepository(applicationContext.assets)
    }
    val quizReviewService by lazy {
        com.example.myapplication.data.quiz.QuizReviewService(database,
            com.example.myapplication.data.quiz.QuizReviewDraftStore(applicationContext.filesDir), roomDocumentAccess,
            identityProvider = { sessionManager.identity.first() })
    }

    /** 幂等交卷服务（事务 CAS + 统计只消费一次）。 */
    val quizSubmissionService: QuizSubmissionService by lazy {
        QuizSubmissionService(database, quizRepository)
    }
    /** 修分/自评服务（grade_revision CAS）。 */
    val gradeAssessmentService: GradeAssessmentService by lazy {
        GradeAssessmentService(database, quizRepository)
    }
    val aiReviewAdoption: AiReviewAdoption by lazy { AiReviewAdoption(gradeAssessmentService) }
    val quizAiReviewService: QuizAiReviewService by lazy {
        QuizAiReviewService(
            database = database,
            profileRepository = profileRepository,
            profileRuntime = profileRuntime,
            transport = ktorTransport,
            toggles = enhancementToggles,
            adoption = aiReviewAdoption,
        )
    }
    /** 笔记修改提议：创建/确认（同事务 CAS）/拒绝/查询（复用二期 NoteRepository 端口）。 */
    val noteProposalService: NoteProposalService by lazy {
        NoteProposalService(database = database, noteRepository = noteRepository)
    }

    /** 随堂测生成器：当前 CHAT profile 单轮固定出题（真实端点未验证，见类注释）。 */
    val chatQuizDraftSource: ChatQuizDraftSource by lazy {
        ChatQuizDraftSource.fromContainer(
            database = database,
            profileRepository = profileRepository,
            profileRuntime = profileRuntime,
            transport = ktorTransport,
        )
    }

    /** 随堂测生成协调（workflow=QUIZ 独立 conversation/run）。 */
    val quizGenerationCoordinator: QuizGenerationCoordinator by lazy {
        QuizGenerationCoordinator(
            database = database,
            conversations = conversationRepository,
            documentAccess = roomDocumentAccess,
            catalog = database.readerCatalogDao(),
            scope = applicationScope,
            // 生产 draftSource：不再让 start() 恒返回 CAPABILITY_UNSUPPORTED；
            // 无 CHAT profile/能力不支持时由生成器返回 CapabilityUnsupported，整卷 FAILED（不伪造成 READY）。
            draftSource = chatQuizDraftSource,
            reviewDraftStore = com.example.myapplication.data.quiz.QuizReviewDraftStore(
                applicationContext.filesDir,
            ),
        )
    }
    /** 来源跳转（持久 source reference + 固定 revision 核验；失效保留 quote）。 */
    internal val historicalSourceAccess by lazy {
        RoomDocumentAccess.forHistoricalReading(database, documentStorage, applicationContext.assets)
    }

    /** Explicit integration entry; the default reader does not open this runtime. */
    val isolatedPdfRuntimeFactory by lazy {
        com.example.myapplication.ui.knowledge.reader.PdfReaderRuntimeFactory(
            historicalSourceAccess, roomDocumentAccess, documentStorage, readerRepository,
        ) { sessionManager.identity.first() }
    }
    /** Application-lived pending owners survive an explicit reflow screen being destroyed. */
    val isolatedReflowCleanupRegistry by lazy {
        com.example.myapplication.ui.knowledge.reader.ReflowCleanupRegistry()
    }

    /** Explicit host binding only; default reading navigation does not call this factory. */
    fun createIsolatedReflowRuntimeFactory(
        host: com.example.myapplication.ui.knowledge.reader.ReflowRuntimeHost,
    ): com.example.myapplication.ui.knowledge.reader.ReflowReaderRuntimeFactory {
        val builtIn = com.example.myapplication.data.reader.BuiltInReflowResourcePreparer(
            historicalSourceAccess, readerRepository,
            { path -> applicationContext.assets.open(path).use { it.readBytes() } },
        )
        val preparer = com.example.myapplication.data.reader.ReflowResourcePreparer(
            com.example.myapplication.data.reader.ReaderResourcePreparer(historicalSourceAccess, documentStorage),
            historicalSourceAccess, documentStorage, builtIn::prepare,
        )
        return com.example.myapplication.ui.knowledge.reader.ReflowReaderRuntimeFactory(
            preparer::prepare, historicalSourceAccess,
            { context, target ->
                when (val writer = com.example.myapplication.data.reader.ReaderProgressCommitter.open(
                    context, target.documentId, target.contentRevision, roomDocumentAccess, readerRepository,
                    { sessionManager.identity.first() },
                )) {
                    is com.example.myapplication.domain.reader.OperationResult.Ok ->
                        com.example.myapplication.domain.reader.OperationResult.Ok(
                            object : com.example.myapplication.ui.knowledge.reader.ReflowProgressSink {
                                override suspend fun commit(locator: com.example.myapplication.domain.reader.ReaderLocator) = writer.value.commit(locator)
                                override suspend fun close() = writer.value.close()
                            }, context.requestId,
                        )
                    is com.example.myapplication.domain.reader.OperationResult.Rejected -> writer
                    is com.example.myapplication.domain.reader.OperationResult.Failed -> writer
                    is com.example.myapplication.domain.reader.OperationResult.Conflict -> writer
                }
            },
            { sessionManager.identity.first() }, host, isolatedReflowCleanupRegistry,
        )
    }
    /** Isolated course entry; approval policy stays explicit. */
    suspend fun openIsolatedCourseReflowRuntime(
        factory: com.example.myapplication.ui.knowledge.reader.ReflowReaderRuntimeFactory,
        context: com.example.myapplication.domain.reader.AccessContext,
        bookId: String,
        request: com.example.myapplication.domain.reader.ReaderOpenRequest,
        allowDevelopmentDraft: Boolean = false,
        preferences: com.example.myapplication.domain.reader.ReaderLayoutPreferences? = null,
    ): com.example.myapplication.domain.reader.OperationResult<com.example.myapplication.ui.knowledge.reader.ReflowReaderRuntime> {
        if (request.target.courseBookId != bookId)
            return com.example.myapplication.domain.reader.OperationResult.Rejected("COURSE_TARGET_MISMATCH")
        return factory.openCourse(context, request,
            { courseReadingSequenceProvider.load(context, bookId, allowDevelopmentDraft) }, preferences)
    }

    val sourceNavigator: SourceNavigator by lazy {
        SourceNavigator(
            references = PersistentSourceReferencePort(sourceReferenceStore),
            verifier = SourceVerifier(historicalSourceAccess),
            encyclopediaSnapshot = {
                encyclopediaPackRepository.currentSnapshot
                    ?: (encyclopediaPackRepository.loadIfNeeded() as? com.example.myapplication.data.encyclopedia.EncyclopediaLoadState.Ready)?.snapshot
            },
        )
    }

    /** UI 订阅随堂测生成进度（按 generationRunId 恢复；workflow=QUIZ 的独立 run）。 */
    fun observeQuizGeneration(
        userId: Long,
        generationRunId: String,
    ): kotlinx.coroutines.flow.Flow<com.example.myapplication.data.quiz.GenerationProgress> =
        quizGenerationCoordinator.observeGeneration(userId, generationRunId)

    /** 唤醒索引 Worker（唯一工作名按用户；任务状态权威仍是 outbox）。 */
    fun scheduleIndexing(userId: Long) {
        if (userId <= 0L) return
        com.example.myapplication.data.knowledge.index.IndexScheduler.enqueue(applicationContext, userId)
    }

    /** 后台 OCR：每本书一个 unique work，处理器自己判断是否真的有扫描页。 */
    fun scheduleOcr(userId: Long, documentId: String) {
        if (userId <= 0L || documentId.isBlank()) return
        OcrScheduler.enqueue(applicationContext, userId, documentId)
    }

    // ---- 第五期：导出与恢复（W5-05 / W5-06 接线） ----
    /**
     * 备份包根目录：`cache/exports`。
     *
     * 必须与 `res/xml/file_paths.xml` 的 `<cache-path path="exports/">` 一致：
     * FileProvider 只暴露这个目录，别处的文件即使拿到路径也给不出 content:// URI。
     */
    val backupExportsRoot: File by lazy { File(applicationContext.cacheDir, "exports") }

    /** 导出：目录式写包（staging → rename），再由 [backupArchive] 打成单文件 zip。 */
    val backupPackageWriter: BackupPackageWriter by lazy {
        BackupPackageWriter(
            database = database,
            inkStore = inkStore,
            exportsRoot = backupExportsRoot,
            appVersion = appVersionName(),
        )
    }

    /** 恢复：校验包 → staging → 单事务提交；只归当前用户。 */
    val restoreCoordinator: RestoreCoordinator by lazy {
        RestoreCoordinator(
            database = database,
            inkStore = inkStore,
            packageRoot = backupExportsRoot,
            stagingRoot = File(backupExportsRoot, ".restore"),
            identityProvider = { sessionManager.identity.first() },
        )
    }

    /** ZIP 封装/解包（带 zip-slip 与炸弹上限）。 */
    val backupArchive: BackupArchive by lazy { BackupArchive() }

    val backupV2RevisionSnapshot: BackupV2RevisionSnapshot by lazy { BackupV2RevisionSnapshot(documentStorage) }
    val backupV2RoomStore: BackupV2RoomStore by lazy {
        BackupV2RoomStore(database, { sessionManager.identity.first() },
            BackupV2PublicationVerifier { backupV2RevisionSnapshot.verifyPublication(it) },
            proofVerifier = BackupV2ProofVerifier { backupV2RevisionSnapshot.verifyProof(it) },
            courseVerifier = AndroidBackupV2CourseVerifier(documentStorage) { path -> applicationContext.assets.open(path).use { it.readBytes() } })
    }
    val backupV2Journal: RestoreJournalV2 by lazy { RestoreJournalV2(File(applicationContext.filesDir.canonicalFile, "backup-v2-journal")) }
    val backupV2RestoreCoordinator: BackupV2RestoreCoordinator by lazy {
        BackupV2RestoreCoordinator(backupV2Journal, backupV2RevisionSnapshot, backupV2RoomStore)
    }
    /** One app lifetime service shared by Settings and Shelf; ViewModels only subscribe. */
    internal val dataManagementBackend: RoomBackupV2ServiceBackend by lazy {
        RoomBackupV2ServiceBackend(
            database, applicationContext.contentResolver, { sessionManager.identity.first() },
            backupPackageWriter, backupArchive, restoreCoordinator, backupExportsRoot,
            File(applicationContext.filesDir.canonicalFile, "backup-service"), backupV2RoomStore, backupV2RevisionSnapshot,
            BackupV2PackageWriter(backupV2RevisionSnapshot), backupV2RestoreCoordinator, appVersionName(), backupV2VerifiedSupport,
            v3 = com.example.myapplication.data.reader.export.BackupV3RoomSupport(
                database, sessionManager, sourcePracticeArchiveRepository, encyclopediaPackRepository,
            ),
        )
    }
    val dataManagementService: BackupV2Service by lazy {
        BackupV2Service(sessionManager.identity, { sessionManager.identity.first() }, dataManagementBackend, applicationScope)
    }
    // ---- 第五期：AI 增强开关与联网检索（W5-10） ----
    /**
     * 联网检索 / AI 评分复核的逐用户开关（DataStore，默认关闭、可撤回）。
     *
     * 不用数据库表：这是设备级偏好，不参与同步/统计，也不需要事务；
     * 新增表会把 schema 推进一格，违反本期"不动 schema v6"的约束。
     */
    val enhancementToggles: EnhancementToggles by lazy {
        DataStoreEnhancementToggles(applicationContext)
    }

    /** SearXNG endpoint is device-local, explicit, and separate from per-user search authorization. */
    val externalSearchSettings: ExternalSearchSettings by lazy {
        DataStoreExternalSearchSettings(applicationContext)
    }
    val externalSearchPort: ExternalSearchPort by lazy {
        SearxngExternalSearchPort(externalSearchSettings)
    }

    /**
     * 离线 OCR 端口（W5-03）。
     *
     * 选型：ML Kit bundled Chinese（见 ADR-002）。模型随 APK 分发，不依赖 Play 服务在线下载；
     * 引擎不返回置信度，质量只用启发式并记录算法版本。低质量页须由当前文档所有者逐页确认。
     */
    val ocrEngine: OcrEngine by lazy { MlKitOcrEngine() }

    /** OCR 逐页检查点：文件缓存，不写 Room（schema v6 冻结，可随时重建）。 */
    val ocrCheckpointStore: OcrCheckpointStore by lazy {
        OcrCheckpointStore(File(applicationContext.filesDir, "ocr-checkpoints"))
    }

    /** 逐页 OCR 执行器：按页落检查点、任务键变化即重来；调度与发布链路待接。 */
    val ocrWorker: OcrWorker by lazy {
        OcrWorker(engine = ocrEngine, store = ocrCheckpointStore)
    }

    /** OCR 发布：派生新内容代次 + 切当前 revision + 登记新 generation 索引（不覆盖源 PDF）。 */
    val ocrRevisionPublisher: OcrRevisionPublisher by lazy {
        OcrRevisionPublisher(storage = documentStorage, database = database)
    }

    /** 一次完整 OCR：选缺文字页 → 逐页检查点 → 发布新代次。 */
    val ocrDocumentProcessor: OcrDocumentProcessor by lazy {
        OcrDocumentProcessor(
            database = database,
            storage = documentStorage,
            worker = ocrWorker,
            publisher = ocrRevisionPublisher,
            store = ocrCheckpointStore,
        )
    }

    /** 当前文档所有者对单页 OCR 结果的显式复核；源哈希变化时拒绝复核。 */
    val ocrReviewService: OcrReviewService by lazy {
        OcrReviewService(database = database, store = ocrCheckpointStore)
    }

    private fun appVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0).versionName
    }.getOrNull().orEmpty().ifBlank { "unknown" }

    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val updateCheck by lazy { createAppUpdateCheck(context.applicationContext, applicationScope) }
    internal val updateInstaller by lazy { UpdateInstaller(context.applicationContext, applicationScope) }
}

