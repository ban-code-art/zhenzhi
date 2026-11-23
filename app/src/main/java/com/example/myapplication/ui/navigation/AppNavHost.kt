package com.example.myapplication.ui.navigation

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.LocalOwnersProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.myapplication.LearningApplication
import com.example.myapplication.AppContainer
import com.example.myapplication.core.AppLog
import com.example.myapplication.data.agent.evidence.SourceNavigator
import com.example.myapplication.data.session.SessionIdentity
import com.example.myapplication.data.session.SessionManager
import com.example.myapplication.domain.reader.AccessContext
import com.example.myapplication.domain.reader.ReaderLocatorCodec
import com.example.myapplication.ui.ai.AgentLaunchContext
import com.example.myapplication.ui.ai.AgentScreen
import com.example.myapplication.ui.ai.AgentSourceUi
import com.example.myapplication.ui.auth.LoginScreen
import com.example.myapplication.ui.knowledge.review.KnowledgeReviewRoute
import com.example.myapplication.ui.auth.RegisterScreen
import com.example.myapplication.ui.components.UiAction
import com.example.myapplication.ui.components.UiGlassBackdrop
import com.example.myapplication.ui.components.UiNoticeHost
import com.example.myapplication.ui.components.UiNoticeVisuals
import com.example.myapplication.ui.components.rememberUiBackdrop
import com.example.myapplication.ui.mine.GlassPageBarHeight
import com.example.myapplication.ui.mine.SubPageScaffold
import com.example.myapplication.ui.theme.UiColorRole
import com.example.myapplication.ui.theme.UiRoles
import com.example.myapplication.ui.theme.UiSurfaceRole
import com.example.myapplication.ui.knowledge.notebook.NotebookScreen
import com.example.myapplication.ui.knowledge.quiz.QuizGeneratingRoute
import com.example.myapplication.ui.knowledge.quiz.QuizRegeneratingRoute
import com.example.myapplication.ui.knowledge.quiz.QuizHistoryRoute
import com.example.myapplication.ui.knowledge.quiz.QuizResultRoute
import com.example.myapplication.ui.knowledge.quiz.QuizScreenRoute
import com.example.myapplication.ui.knowledge.quiz.SourcePracticeRoute
import com.example.myapplication.ui.knowledge.quiz.SourcePracticeArchiveRoute
import com.example.myapplication.ui.knowledge.quiz.SourcePracticeChapterQa
import com.example.myapplication.data.quiz.SourcePracticeScope
import com.example.myapplication.ui.knowledge.reader.ReaderScreen
import com.example.myapplication.ui.main.MainScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.example.myapplication.ui.knowledge.ImportedLibraryRoute
import com.example.myapplication.ui.knowledge.course.*
import com.example.myapplication.domain.reader.ReaderPlacement
import com.example.myapplication.domain.reader.ReaderOpenRequest
import kotlinx.serialization.json.*
import com.example.myapplication.data.encyclopedia.EncyclopediaEvidenceVerifier
import com.example.myapplication.data.encyclopedia.EncyclopediaLoadState
import com.example.myapplication.data.encyclopedia.EncyclopediaSourceLocator
import com.example.myapplication.data.encyclopedia.EncyclopediaVerificationResult
import com.example.myapplication.ui.encyclopedia.EncyclopediaSourceDestinationScreen

@Composable
fun AppNavHost(startDestination: String, navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionManager = remember { SessionManager(context.applicationContext) }
    val container = remember {
        (context.applicationContext as LearningApplication).container
    }
    val returnStore = remember {
        ReturnPointStore(File(context.applicationContext.noBackupFilesDir, "reader-return"), {
            val identity = sessionManager.identity.first()
            identity.userId?.let { ReturnSession(it, identity.epoch) }
        })
    }
    val notice = remember { androidx.compose.material3.SnackbarHostState() }
    var chapterQa by remember { mutableStateOf<SourcePracticeScope?>(null) }
    var chapterQaOwner by remember { mutableStateOf<String?>(null) }
    val qaIdentity by sessionManager.identity.collectAsState(initial = null)
    val activeEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(qaIdentity) { chapterQa = null; chapterQaOwner = null }

    fun openChapterQa(bound: SourcePracticeScope) {
        chapterQaOwner = navController.currentBackStackEntry?.id
        chapterQa = bound
    }

    suspend fun showNavNotice(entry: NavBackStackEntry, origin: SessionIdentity, message: String) {
        if (sessionManager.identity.first() == origin && navController.currentBackStackEntry?.id == entry.id) {
            notice.showSnackbar(
                UiNoticeVisuals(
                    eventId = UUID.randomUUID().toString(),
                    ownerKey = entry.id to origin,
                    message = message,
                ),
            )
        }
    }

    fun openGeneratedAttempt(entry: NavBackStackEntry, owner: SessionIdentity?, attemptId: String) {
        if (owner?.userId == null) return
        scope.launch(Dispatchers.Main.immediate) {
            if (sessionManager.identity.first() != owner ||
                navController.currentBackStackEntry?.id != entry.id ||
                entry.lifecycle.currentState != androidx.lifecycle.Lifecycle.State.RESUMED
            ) return@launch
            navController.navigate(Routes.quizAttempt(attemptId)) { launchSingleTop = true }
        }
    }

    fun callerRoute(entry: NavBackStackEntry): String = when (entry.destination.route) {
        Routes.READER -> Routes.reader(entry.arguments?.getString("documentId").orEmpty(),
            returnKey = entry.arguments?.getString("returnKey")?.takeIf { it.isNotBlank() })
        Routes.COURSE_BOOK -> Routes.courseBook(entry.arguments?.getString("bookId").orEmpty())
        Routes.COURSE_LIBRARY -> Routes.COURSE_LIBRARY
        Routes.IMPORTED_LIBRARY -> Routes.IMPORTED_LIBRARY
        Routes.COURSE_READER -> Routes.courseReader(entry.arguments?.getString("bookId").orEmpty(),
            entry.arguments?.getString("chapterKey").orEmpty(),
            ReaderPlacement.AT_LOCATOR, entry.arguments?.getString("returnKey").orEmpty(),
            source = entry.arguments?.getString("source").toBoolean())
        Routes.SOURCE_READER -> Routes.sourceReader(entry.arguments?.getString("returnKey").orEmpty())
        Routes.ENCYCLOPEDIA_SOURCE -> Routes.encyclopediaSource(entry.arguments?.getString("returnKey").orEmpty())
        Routes.AGENT -> entry.arguments?.getString("returnKey")?.takeIf { it.isNotBlank() }
            ?.let(Routes::agent) ?: "agent"
        Routes.QUIZ_REVIEW -> Routes.quizReview(entry.arguments?.getString("paperId"))
        Routes.QUIZ_RESULT -> Routes.quizResult(entry.arguments?.getString("attemptId").orEmpty())
        else -> Routes.HOME
    }

    suspend fun saveReturn(entry: NavBackStackEntry, launch: AgentLaunchContext, referenceId: String? = null): String {
        val identity = sessionManager.identity.first()
        val userId = identity.userId ?: error("请先登录")
        val locator = launch.locatorJson?.let { ReaderLocatorCodec.decodeOrNull(it) }
        return returnStore.put(ReturnPoint(ownerUserId = userId, sessionEpoch = identity.epoch,
            routeInstanceKey = entry.id, callerRouteKey = callerRoute(entry), documentId = launch.documentId,
            revision = locator?.contentRevision, locatorJson = launch.locatorJson,
            positionStateJson = if (entry.destination.route == Routes.IMPORTED_LIBRARY)
                entry.savedStateHandle.get<String>("importedShelfNavigation") ?: "{}"
            else entry.savedStateHandle.get<String>("returnPosition") ?: "{}"),
            withContext(Dispatchers.Default) { ReturnPayloadCodec.encode(launch, referenceId) })
    }

    fun openAgent(entry: NavBackStackEntry, launch: AgentLaunchContext) {
        scope.launch(Dispatchers.Main.immediate) {
            val origin = sessionManager.identity.first()
            try {
                val key = saveReturn(entry, launch)
                if (navController.currentBackStackEntry?.id == entry.id) navController.navigate(Routes.agent(key))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                AppLog.e("navigation/agent", "打开工作区失败", failure)
                showNavNotice(entry, origin, failure.message ?: "无法保存返回现场，请重试")
            }
        }
    }

    fun restoreCallerState(entry: NavBackStackEntry, point: ReturnPoint) {
        entry.savedStateHandle["returnPosition"] = point.positionStateJson
        when (entry.destination.route) {
            Routes.IMPORTED_LIBRARY -> entry.savedStateHandle["importedShelfNavigation"] = point.positionStateJson
            Routes.READER, Routes.COURSE_READER, Routes.SOURCE_READER -> entry.savedStateHandle["returnLocator"] = point.locatorJson
        }
    }

    fun returnToCaller(point: ReturnPoint?) {
        val previous = navController.previousBackStackEntry
        if (previous != null && (point == null || previous.id == point.routeInstanceKey)) {
            if (point != null) restoreCallerState(previous, point)
            navController.popBackStack()
        } else {
            navController.navigate(point?.callerRouteKey ?: Routes.HOME) {
                popUpTo(Routes.HOME) { inclusive = false }
                launchSingleTop = true
            }
            if (point != null) navController.currentBackStackEntry?.let { restoreCallerState(it, point) }
        }
    }

    fun openSource(entry: NavBackStackEntry, source: AgentSourceUi) {
        scope.launch(Dispatchers.Main.immediate) {
            val requestedSession = sessionManager.identity.first()
            try {
                if (EncyclopediaSourceLocator.decode(source.locatorJson) != null) {
                    val encLocator = resolveEncyclopediaSourceTarget(container, sessionManager, source)
                        ?: error("百科来源已失效或无权访问，历史摘录仍保留在原页面")
                    check(sessionManager.identity.first() == requestedSession) { "登录会话已变化，请重新打开" }
                    val launch = AgentLaunchContext(
                        documentTitle = source.documentTitle,
                        quote = source.quote,
                        locatorJson = EncyclopediaSourceLocator.encode(encLocator),
                    )
                    val key = saveReturn(entry, launch, source.referenceId)
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        navController.navigate(Routes.encyclopediaSource(key))
                    }
                    return@launch
                }
                val target = resolveSourceTarget(container, sessionManager, source)
                    ?: error("来源已失效，历史摘录仍保留在原页面")
                check(sessionManager.identity.first() == requestedSession) { "登录会话已变化，请重新打开" }
                val launch = AgentLaunchContext(documentId = target.first, documentTitle = source.documentTitle,
                    quote = source.quote, locatorJson = target.second)
                val key = saveReturn(entry, launch, source.referenceId)
                if (navController.currentBackStackEntry?.id == entry.id) navController.navigate(Routes.sourceReader(key))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { showNavNotice(entry, requestedSession, failure.message ?: "无法保存返回现场，请重试") }
        }
    }

    val motionDirection = if (androidx.compose.ui.platform.LocalLayoutDirection.current ==
        androidx.compose.ui.unit.LayoutDirection.Rtl) -1 else 1
    val noticeBackdrop = rememberUiBackdrop()
    androidx.compose.foundation.layout.Box {
    UiGlassBackdrop(noticeBackdrop, captureEnabled = notice.currentSnackbarData != null) {
    UiNavigationFrame(navigationKey = activeEntry?.id) {
    val pageBackPreview = LocalPageBackPreview.current
    val homeState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val visibleEntries by navController.visibleEntries.collectAsState()
    val homeEntry = remember(activeEntry) { runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrNull() }
    homeEntry?.let { entry ->
        androidx.compose.runtime.key(entry.id) {
            entry.LocalOwnersProvider(homeState) {
                RetainedNavigationHome(activeEntry?.id == entry.id, visibleEntries.any { it.id == entry.id }, motionDirection) {
            val pendingEncJump by entry.savedStateHandle.getStateFlow<String?>("encPendingJump", null).collectAsState()
            MainScreen(
                onLogout = {
                    scope.launch(Dispatchers.Main.immediate) {
                        // 登出先取消未完成的外发（缓存按用户清空），再清会话。
                        val userId = sessionManager.currentUserId.first()
                        // After logout none of this session's navigation points can be resumed.
                        if (userId != null) returnStore.collectGarbage(userId, emptySet())
                        sessionManager.logout()
                        if (userId != null) {
                            container.queryEmbeddingService.evictUser(userId)
                        }
                        navController.navigate(Routes.LOGIN) { popUpTo(Routes.HOME) { inclusive = true } }
                    }
                },
                onOpenReader = { documentId ->
                    if (navController.currentBackStackEntry?.id == entry.id) navController.navigate(Routes.reader(documentId))
                },
                onOpenQuizHistory = { navController.navigate(Routes.QUIZ_HISTORY) },
                onOpenKnowledgeReview = { navController.navigate(Routes.KNOWLEDGE_REVIEW) { launchSingleTop = true } },
                onOpenFavoriteSource = { openSource(entry, it) },
                initialReturnPosition = entry.savedStateHandle.get<String>("returnPosition") ?: "{}",
                onSourceCheckpoint = { entry.savedStateHandle["returnPosition"] = it },
                onOpenCourseLibrary = { navController.navigate(Routes.COURSE_LIBRARY) },
                onOpenImportedLibrary = { navController.navigate(Routes.IMPORTED_LIBRARY) },
                onOpenBundledCourse = {
                    if (navController.currentBackStackEntry?.id == entry.id) navController.navigate(Routes.bundledCourse(it))
                },
                onOpenSourcePracticeArchive = { id, question -> navController.navigate(Routes.sourcePracticeArchive(id, question)) },
                onOpenEncyclopediaBundledChapter = { bookKey, chapterKey, quote ->
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        val route = "chapter-source/${Uri.encode(bookKey)}/${Uri.encode(chapterKey)}" +
                            "?quote=${Uri.encode(quote.orEmpty())}&revision="
                        navController.navigate(route) { launchSingleTop = true }
                    }
                },
                onAskEncyclopediaAi = { encCtx ->
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        openAgent(entry, AgentLaunchContext(encyclopediaContext = encCtx))
                    }
                },
                pendingEncyclopediaJump = pendingEncJump,
                onEncyclopediaJumpConsumed = { entry.savedStateHandle["encPendingJump"] = null },
            )
                }
            }
        }
    }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { uiPageEnter(forward = true, direction = motionDirection) },
        exitTransition = { uiPageExit(forward = true, direction = motionDirection) },
        popEnterTransition = { uiPageEnter(forward = false, direction = motionDirection, preview = pageBackPreview) },
        popExitTransition = { uiPageExit(forward = false, direction = motionDirection, preview = pageBackPreview) },
    ) {
        composable(Routes.LOGIN) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                    scope.launch(Dispatchers.Main.immediate) {
                        // 登录后才允许后台索引：未登录不跑任何外发。
                        val userId = sessionManager.currentUserId.first() ?: return@launch
                        container.scheduleIndexing(userId)
                    }
                },
                onGoRegister = { navController.navigate(Routes.REGISTER) },
            )
        }
        composable(Routes.REGISTER) {
            RegisterScreen(
                onRegisterSuccess = {
                    navController.navigate(Routes.HOME) { popUpTo(Routes.LOGIN) { inclusive = true } }
                    scope.launch(Dispatchers.Main.immediate) {
                        val userId = sessionManager.currentUserId.first() ?: return@launch
                        container.scheduleIndexing(userId)
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.HOME) { Box(Modifier.fillMaxSize()) }
        composable(Routes.KNOWLEDGE_REVIEW) {
            KnowledgeReviewRoute(
                repository = container.reviewRepository,
                sources = container.reviewSources,
                sessionManager = sessionManager,
                onBack = { navController.popBackStack() },
                onSource = { destination ->
                    if (destination.startsWith("meridian-acupoint/")) {
                        val pointCode = destination.removePrefix("meridian-acupoint/")
                        homeEntry?.savedStateHandle?.set(
                            "encPendingJump",
                            "${UUID.randomUUID()}|acupoint|$pointCode",
                        )
                        navController.popBackStack(Routes.HOME, inclusive = false)
                    } else {
                        navController.navigate(destination)
                    }
                },
            )
        }
        composable(Routes.COURSE_LIBRARY) {
            CourseLibraryRoute({ navController.navigate(Routes.courseBook(it)) }, { navController.popBackStack() },
                onOpenDraft = { navController.navigate("private-course-draft-preview") },
                onAskAi = ::openChapterQa)
        }
        composable("private-course-draft-preview") {
            PrivateCourseDraftPreviewRoute(onBack = { navController.popBackStack() }, onAskAi = ::openChapterQa)
        }
        composable(Routes.BUNDLED_COURSE, arguments = listOf(navArgument("bookKey") { type = NavType.StringType })) { entry ->
            BundledCourseDraftPreviewRoute(onBack = { navController.popBackStack() },
                initialBookKey = entry.arguments?.getString("bookKey"), onAskAi = ::openChapterQa)
        }
        composable("chapter-source/{bookKey}/{chapterKey}?quote={quote}&revision={revision}", arguments = listOf(
            navArgument("bookKey") { type = NavType.StringType },
            navArgument("chapterKey") { type = NavType.StringType },
            navArgument("quote") { type = NavType.StringType },
            navArgument("revision") { type = NavType.StringType },
        )) { entry ->
            BundledCourseDraftPreviewRoute(onBack = { navController.popBackStack() },
                initialBookKey = entry.arguments?.getString("bookKey"),
                initialChapterKey = entry.arguments?.getString("chapterKey"),
                sourceQuote = entry.arguments?.getString("quote"),
                sourceRevision = entry.arguments?.getString("revision"))
        }
        composable(Routes.COURSE_BOOK, arguments = listOf(navArgument("bookId") { type = NavType.StringType })) { entry ->
            val position by entry.savedStateHandle.getStateFlow("returnPosition", "{}").collectAsState()
            CourseBookRoute(entry.arguments?.getString("bookId").orEmpty(), position,
                onPosition = { entry.savedStateHandle["returnPosition"] = it },
                onOpen = { request, saved -> scope.launch(Dispatchers.Main.immediate) {
                    val origin = sessionManager.identity.first()
                    try {
                        entry.savedStateHandle["returnPosition"] = saved
                        val key = saveReturn(entry, AgentLaunchContext(documentId = request.target.documentId,
                            locatorJson = request.locator?.let(ReaderLocatorCodec::encode)))
                        if (navController.currentBackStackEntry?.id == entry.id)
                            navController.navigate(Routes.courseReader(request.target.courseBookId.orEmpty(), request.target.chapterKey.orEmpty(),
                                request.placement, key, request.locator?.let(ReaderLocatorCodec::encode)))
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { showNavNotice(entry, origin, failure.message ?: "无法保存目录现场") }
                } }, onBack = { navController.popBackStack() })
        }
        composable(Routes.IMPORTED_LIBRARY) { entry ->
            val position by entry.savedStateHandle.getStateFlow("importedShelfNavigation", "{}").collectAsState()
            ImportedLibraryRoute(onOpenReader = { documentId -> scope.launch(Dispatchers.Main.immediate) {
                val origin = sessionManager.identity.first()
                // 保存返回现场失败时仍要打开书：现场丢失可以下次补，点书没反应会让整本书不可达。
                val key = try {
                    saveReturn(entry, AgentLaunchContext(documentId = documentId))
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLog.w("navigation/imported", "保存书架现场失败，直接打开 $documentId", failure)
                    showNavNotice(entry, origin, failure.message ?: "无法保存书架现场，已直接打开书籍")
                    null
                }
                try {
                    if (navController.currentBackStackEntry?.id == entry.id) {
                        navController.navigate(if (key != null) Routes.reader(documentId, returnKey = key) else Routes.reader(documentId))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    AppLog.e("navigation/imported", "打开阅读器失败 $documentId", failure)
                    showNavNotice(entry, origin, failure.message ?: "无法打开这本书")
                }
            } }, onBack = { navController.popBackStack() }, initialPosition = position,
                onPosition = { snapshot ->
                    entry.savedStateHandle["importedShelfNavigation"] = snapshot
                    entry.savedStateHandle["returnPosition"] = snapshot
                })
        }
        composable(Routes.COURSE_READER, arguments = listOf(
            navArgument("bookId") { type = NavType.StringType }, navArgument("chapterKey") { type = NavType.StringType },
            navArgument("placement") { type = NavType.StringType; defaultValue = "START" },
            navArgument("locator") { type = NavType.StringType; defaultValue = "" },
            navArgument("returnKey") { type = NavType.StringType },
            navArgument("source") { type = NavType.StringType; defaultValue = "false" },
        )) { entry ->
            val book = entry.arguments?.getString("bookId").orEmpty()
            val chapter = entry.arguments?.getString("chapterKey").orEmpty()
            val key = entry.arguments?.getString("returnKey").orEmpty()
            val source = entry.arguments?.getString("source").toBoolean()
            val returnLocator by entry.savedStateHandle.getStateFlow<String?>("returnLocator", null).collectAsState()
            ReturnDestination(key, false, returnStore, sessionManager, container, ::returnToCaller) { point, _ ->
                val consumedPosition = remember(entry.id, point.ownerUserId, point.sessionEpoch) {
                    entry.savedStateHandle.get<String>("courseReaderConsumed")
                }
                val placement = runCatching { ReaderPlacement.valueOf(entry.arguments?.getString("placement").orEmpty()) }.getOrDefault(ReaderPlacement.START)
                CourseReaderRoute(book, chapter, placement,
                    entry.arguments?.getString("locator")?.takeIf { it.isNotBlank() },
                    onBack = { returnToCaller(point) }, onNavigate = { request ->
                        if (navController.currentBackStackEntry?.id == entry.id) {
                            navController.navigate(Routes.courseReader(book, request.target.chapterKey.orEmpty(), request.placement, key, source = source)) {
                                popUpTo(entry.destination.id) { inclusive = true }
                            }
                        }
                    }, onOpenNotebook = { navController.navigate(Routes.notebook(it)) },
                    onAskAi = { openAgent(entry, it) }, onGenerateQuiz = { doc, section -> navController.navigate(Routes.sourcePractice(doc, section)) },
                    isSourceReader = source, consumedPositionJson = consumedPosition, returnLocatorJson = returnLocator, onPositionReady = { raw ->
                        // Mark START/END consumed only after a real, validated renderer position exists.
                        if (navController.currentBackStackEntry?.id == entry.id) entry.savedStateHandle["courseReaderConsumed"] =
                            courseConsumed(point.ownerUserId, point.sessionEpoch, book, chapter, raw)
                    })
            }
        }
        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                // 来源卡片带入的持久 Locator；空值表示按上次进度打开。
                navArgument("locator") { type = NavType.StringType; defaultValue = "" },
                navArgument("returnKey") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val documentId = entry.arguments?.getString("documentId").orEmpty()
            val returnLocator by entry.savedStateHandle.getStateFlow<String?>("returnLocator", null).collectAsState()
            val initialLocator = (returnLocator
                ?: entry.arguments?.getString("locator").orEmpty())
                .takeIf { it.isNotBlank() }
            val key = entry.arguments?.getString("returnKey").orEmpty()
            val screen: @Composable (ReturnPoint?) -> Unit = { point -> ReaderScreen(
                documentId = documentId,
                initialLocatorJson = initialLocator,
                onBack = { if (key.isBlank()) navController.popBackStack() else returnToCaller(point) },
                onOpenNotebook = { navController.navigate(Routes.notebook(documentId)) },
                onAskAi = { launch -> openAgent(entry, launch) },
                onGenerateQuiz = { sourceDocumentId, sectionId ->
                    navController.navigate(Routes.sourcePractice(sourceDocumentId, sectionId))
                },
            ) }
            if (key.isBlank()) screen(null) else ReturnDestination(key, false, returnStore, sessionManager, container, ::returnToCaller) { point, _ -> screen(point) }
        }
        composable(Routes.SOURCE_READER, arguments = listOf(navArgument("returnKey") { type = NavType.StringType })) { entry ->
            val returnLocator by entry.savedStateHandle.getStateFlow<String?>("returnLocator", null).collectAsState()
            ReturnDestination(entry.arguments?.getString("returnKey").orEmpty(), true,
                returnStore, sessionManager, container, ::returnToCaller) { point, launch ->
                val course by produceState(SourceCourseResolution(), point.returnKey, point.locatorJson) {
                    val locator = point.locatorJson?.let(ReaderLocatorCodec::decodeOrNull)
                    value = try {
                        val document = withContext(Dispatchers.IO) { container.database.readerCatalogDao().findDocument(requireNotNull(launch.documentId)) }
                        SourceCourseResolution(false, locator?.let { withContext(Dispatchers.IO) {
                            resolveCourseSource(container, requireNotNull(launch.documentId), it)
                        } }, requiresCourse = document?.kind != "IMPORTED")
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { SourceCourseResolution(false, requiresCourse = true) }
                }
                val binding = course.binding
                if (course.loading) androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(UiRoles.surface(UiSurfaceRole.Page))
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(
                            "正在核对来源章节…",
                            style = UiRoles.text(MaterialTheme.typography.bodyMedium),
                            color = UiRoles.color(UiColorRole.OnSurfaceVariant),
                        )
                    }
                }
                else if (binding != null) {
                    val consumedPosition = remember(entry.id) { entry.savedStateHandle.get<String>("courseReaderConsumed") }
                    CourseReaderRoute(binding.bookId, binding.chapterKey, ReaderPlacement.AT_LOCATOR, point.locatorJson,
                        onBack = { returnToCaller(point) }, onNavigate = { request ->
                            if (navController.currentBackStackEntry?.id == entry.id) navController.navigate(Routes.courseReader(binding.bookId,
                                request.target.chapterKey.orEmpty(), request.placement, entry.arguments?.getString("returnKey").orEmpty(), source = true)) {
                                popUpTo(entry.destination.id) { inclusive = true }
                            }
                        }, onOpenNotebook = { navController.navigate(Routes.notebook(it)) }, onAskAi = { openAgent(entry, it) },
                        onGenerateQuiz = { doc, section -> navController.navigate(Routes.sourcePractice(doc, section)) },
                        isSourceReader = true, consumedPositionJson = consumedPosition, returnLocatorJson = returnLocator, onPositionReady = { raw ->
                            if (navController.currentBackStackEntry?.id == entry.id) entry.savedStateHandle["courseReaderConsumed"] =
                                courseConsumed(point.ownerUserId, point.sessionEpoch, binding.bookId, binding.chapterKey, raw)
                        })
                } else if (course.requiresCourse) {
                    val requestBack = unifiedPageBack { returnToCaller(point) }
                    SubPageScaffold(
                        title = "来源核对",
                        onBack = requestBack,
                        glassBack = true,
                        actionId = "sourceReader.back",
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, top = GlassPageBarHeight + 12.dp, end = 20.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                "课程来源暂不可打开，请返回原页面；历史摘录仍保留",
                                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                                color = UiRoles.foreground,
                            )
                            UiAction(
                                label = "返回原页面",
                                onClick = requestBack,
                                filled = true,
                                actionId = "sourceReader.returnToCaller",
                            )
                        }
                    }
                } else ReaderScreen(documentId = requireNotNull(launch.documentId), initialLocatorJson = returnLocator ?: point.locatorJson, isSourceReader = true,
                    onBack = { returnToCaller(point) },
                    onOpenNotebook = { navController.navigate(Routes.notebook(requireNotNull(launch.documentId))) },
                    onAskAi = { reading -> openAgent(entry, reading) },
                    onGenerateQuiz = { documentId, sectionId -> navController.navigate(Routes.sourcePractice(documentId, sectionId)) })
            }
        }
        composable(Routes.ENCYCLOPEDIA_SOURCE, arguments = listOf(navArgument("returnKey") { type = NavType.StringType })) { entry ->
            ReturnDestination(entry.arguments?.getString("returnKey").orEmpty(), true,
                returnStore, sessionManager, container, ::returnToCaller) { point, launch ->
                val locator = remember(point.locatorJson, launch.locatorJson) {
                    EncyclopediaSourceLocator.decode(point.locatorJson ?: launch.locatorJson)
                }
                if (locator != null) {
                    EncyclopediaSourceDestinationScreen(
                        locator = locator,
                        onBack = { returnToCaller(point) },
                        onOpenBundledChapter = { bookKey, chapterKey, quote ->
                            if (navController.currentBackStackEntry?.id == entry.id) {
                                val route = "chapter-source/${Uri.encode(bookKey)}/${Uri.encode(chapterKey)}" +
                                    "?quote=${Uri.encode(quote.orEmpty())}&revision="
                                navController.navigate(route) { launchSingleTop = true }
                            }
                        },
                    )
                } else {
                    val requestBack = unifiedPageBack { returnToCaller(point) }
                    SubPageScaffold(
                        title = "百科来源",
                        onBack = requestBack,
                        glassBack = true,
                        actionId = "encSource.back",
                    ) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(start = 20.dp, top = GlassPageBarHeight + 12.dp, end = 20.dp, bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                "百科来源定位信息已失效，历史摘录仍保留在原对话",
                                style = UiRoles.text(MaterialTheme.typography.bodyLarge),
                                color = UiRoles.foreground,
                            )
                            UiAction(
                                label = "返回原对话",
                                onClick = requestBack,
                                filled = true,
                                actionId = "encSource.returnToCaller",
                            )
                        }
                    }
                }
            }
        }
        composable(
            route = Routes.NOTEBOOK,
            arguments = listOf(navArgument("documentId") { type = NavType.StringType }),
        ) { entry ->
            val documentId = Uri.decode(entry.arguments?.getString("documentId").orEmpty())
            NotebookScreen(documentId = documentId, onBack = { navController.popBackStack() })
        }
        composable(
            route = Routes.AGENT,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType; defaultValue = "" },
                navArgument("documentTitle") { type = NavType.StringType; defaultValue = "" },
                navArgument("sectionId") { type = NavType.StringType; defaultValue = "" },
                navArgument("sectionTitle") { type = NavType.StringType; defaultValue = "" },
                navArgument("quote") { type = NavType.StringType; defaultValue = "" },
                navArgument("locator") { type = NavType.StringType; defaultValue = "" },
                navArgument("returnKey") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val returnKey = entry.arguments?.getString("returnKey").orEmpty()
            if (returnKey.isNotBlank()) {
                ReturnDestination(returnKey, false, returnStore, sessionManager, container, ::returnToCaller) { point, launch ->
                    AgentScreen(
                        launch = launch,
                        onClose = {
                            launch.encyclopediaContext?.returnCheckpointJson?.let { checkpoint ->
                                runCatching { navController.getBackStackEntry(Routes.HOME) }.getOrNull()?.let { home ->
                                    home.savedStateHandle["encPendingJump"] = "${UUID.randomUUID()}|checkpoint|$checkpoint"
                                }
                            }
                            returnToCaller(point)
                        },
                        initialReturnPosition = entry.savedStateHandle.get<String>("returnPosition") ?: "{}",
                        onSourceCheckpoint = { entry.savedStateHandle["returnPosition"] = it },
                        onOpenSource = { source -> openSource(entry, source) },
                    )
                }
            } else AgentScreen(
                launch = Routes.readLegacyAgent { entry.arguments?.getString(it) },
                initialReturnPosition = entry.savedStateHandle.get<String>("returnPosition") ?: "{}",
                onSourceCheckpoint = { entry.savedStateHandle["returnPosition"] = it },
                onClose = { navController.popBackStack() },
                onOpenSource = { source: AgentSourceUi ->
                    // 来源跳转按持久 referenceId 复核后带 Locator 打开阅读器；
                    // 核验失败/已撤权时不跳（卡片本身已标“无法精确跳转”）。
                    openSource(entry, source)
                },
            )
        }
        composable(
            route = Routes.SOURCE_PRACTICE,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                navArgument("sectionId") { type = NavType.StringType; defaultValue = "" },
                navArgument("paperId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val documentId = entry.arguments?.getString("documentId").orEmpty()
            val sectionId = entry.arguments?.getString("sectionId")?.takeIf { it.isNotBlank() }
            val paperId = entry.arguments?.getString("paperId")?.takeIf { it.isNotBlank() }
            SourcePracticeRoute(
                documentId = documentId,
                sectionId = sectionId,
                paperId = paperId,
                onBack = { navController.popBackStack() },
                onGenerateAi = {
                    navController.navigate(if (documentId == "paper" && paperId != null)
                        Routes.quizRegenerating(paperId) else Routes.quizGenerating(documentId, sectionId)) {
                        launchSingleTop = true
                    }
                },
                onAskAi = ::openChapterQa,
            )
        }
        composable(Routes.SOURCE_PRACTICE_ARCHIVE, arguments = listOf(
            navArgument("recordId") { type = NavType.StringType },
            navArgument("questionId") { type = NavType.StringType; defaultValue = "" },
        )) { entry ->
            SourcePracticeArchiveRoute(recordId = entry.arguments?.getString("recordId").orEmpty(),
                onBack = { navController.popBackStack() },
                initialQuestionId = entry.arguments?.getString("questionId")?.takeIf(String::isNotBlank),
                onAskAi = ::openChapterQa)
        }
        composable(
            route = Routes.QUIZ_GENERATING,
            arguments = listOf(
                navArgument("documentId") { type = NavType.StringType },
                navArgument("sectionId") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            val documentId = Uri.decode(entry.arguments?.getString("documentId").orEmpty())
            val sectionId = Uri.decode(entry.arguments?.getString("sectionId").orEmpty()).takeIf { it.isNotBlank() }
            val owner = qaIdentity
            QuizGeneratingRoute(
                documentId = documentId,
                sectionId = sectionId,
                onBackToReading = { navController.popBackStack() },
                onOpenAnswer = { attemptId -> openGeneratedAttempt(entry, owner, attemptId) },
                onOpenReview = { navController.navigate(Routes.quizReview(it)) },
                onOpenPractice = { navController.navigate(Routes.sourcePractice(documentId, sectionId, it)) },
            )
        }
        composable(
            route = Routes.QUIZ_ATTEMPT,
            arguments = listOf(navArgument("attemptId") { type = NavType.StringType }),
        ) { entry ->
            val attemptId = Uri.decode(entry.arguments?.getString("attemptId").orEmpty())
            QuizScreenRoute(
                attemptId = attemptId,
                onBack = { navController.popBackStack() },
                onOpenResult = quizAttemptResultCallback(navController, entry),
            )
        }
        composable(
            route = Routes.QUIZ_RESULT,
            arguments = listOf(navArgument("attemptId") { type = NavType.StringType }),
        ) { entry ->
            val attemptId = Uri.decode(entry.arguments?.getString("attemptId").orEmpty())
            QuizResultRoute(
                attemptId = attemptId,
                initialReturnPosition = entry.savedStateHandle.get<String>("returnPosition") ?: "{}",
                onSourceCheckpoint = { entry.savedStateHandle["returnPosition"] = it },
                onBack = { navController.popBackStack() },
                onOpenAttempt = { attemptId -> navController.navigate(Routes.quizAttempt(attemptId)) },
                onRegenerate = { paperId -> navController.navigate(Routes.quizRegenerating(paperId)) },
                onOpenSource = { source ->
                    // 随堂测出处来自 quiz_items.evidence_json（发布时已核验），带 Locator 精确打开。
                    openSource(entry, source)
                },
            )
        }
        composable(Routes.QUIZ_REVIEW, arguments = listOf(navArgument("paperId") {
            type = NavType.StringType; defaultValue = ""
        })) { entry ->
            val identity by sessionManager.identity.collectAsState(com.example.myapplication.data.session.SessionIdentity(null, 0))
            suspend fun reviewContext(): AccessContext {
                val current = sessionManager.identity.first()
                return AccessContext(current.userId ?: error("请先登录"), current.epoch, "review-${UUID.randomUUID()}")
            }
            com.example.myapplication.ui.knowledge.quiz.QuizReviewRoute(
                service = container.quizReviewService, ctxProvider = { reviewContext() },
                paperId = entry.arguments?.getString("paperId")?.takeIf { it.isNotBlank() },
                identityKey = identity,
                initialReturnPosition = entry.savedStateHandle.get<String>("returnPosition") ?: "{}",
                onSourceCheckpoint = { entry.savedStateHandle["returnPosition"] = it },
                onBack = { navController.popBackStack() },
                onOpenSource = { openSource(entry, it) },
                onRegenerate = { navController.navigate(Routes.quizRegenerating(it)) },
                onOpenPaper = { paperId ->
                    fun traceReviewNavigation(stage: String) {
                        val current = navController.currentBackStackEntry
                        AppLog.w("navigation/quiz-review-diagnostic",
                            "stage=$stage main=${android.os.Looper.myLooper() == android.os.Looper.getMainLooper()} " +
                                "thread=${Thread.currentThread().name} review=${entry.id}/${entry.lifecycle.currentState} " +
                                "current=${current?.id}/${current?.destination?.route}/${current?.lifecycle?.currentState}")
                    }
                    scope.launch(Dispatchers.Main.immediate) {
                        traceReviewNavigation("callback")
                        traceReviewNavigation("scope-start")
                        val requested = sessionManager.identity.first()
                        try {
                            traceReviewNavigation("before-open")
                            val opened = container.quizRepository.openOrResume(reviewContext(), paperId, System.currentTimeMillis())
                            AppLog.w("navigation/quiz-review-diagnostic", "open-result=${opened.javaClass.simpleName}")
                            traceReviewNavigation("after-open")
                            check(sessionManager.identity.first() == requested) { "登录会话已变化" }
                            val result = opened as? com.example.myapplication.data.quiz.OpenAttemptResult.Opened
                                ?: error("无法打开练习")
                            traceReviewNavigation("before-navigate")
                            if (navController.currentBackStackEntry?.id == entry.id) {
                                navController.navigate(Routes.quizAttempt(result.attempt.attemptId))
                                traceReviewNavigation("after-navigate")
                            } else traceReviewNavigation("skipped-stale-entry")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            AppLog.e("navigation/quiz-review-diagnostic", "打开练习失败", failure)
                            traceReviewNavigation("navigation-failure")
                            showNavNotice(entry, requested, failure.message ?: "无法打开练习")
                        }
                    }
                },
            )
        }
        composable(Routes.QUIZ_HISTORY) {
            QuizHistoryRoute(
                onBack = { navController.popBackStack() },
                onOpenResult = { id -> navController.navigate(Routes.quizResult(id)) },
                onOpenAttempt = { id -> navController.navigate(Routes.quizAttempt(id)) },
                onRegenerate = { paperId -> navController.navigate(Routes.quizRegenerating(paperId)) },
                onOpenReview = { navController.navigate(Routes.quizReview(it)) },
                onOpenSourcePracticeRecord = { navController.navigate(Routes.sourcePracticeArchive(it)) },
            )
        }
        composable(
            route = Routes.QUIZ_REGENERATE,
            arguments = listOf(navArgument("paperId") { type = NavType.StringType }),
        ) { entry ->
            val paperId = Uri.decode(entry.arguments?.getString("paperId").orEmpty())
            val owner = qaIdentity
            QuizRegeneratingRoute(
                sourcePaperId = paperId,
                onBackToReading = { navController.popBackStack() },
                onOpenAnswer = { attemptId -> openGeneratedAttempt(entry, owner, attemptId) },
                onOpenReview = { navController.navigate(Routes.quizReview(it)) },
                onOpenPractice = { navController.navigate(Routes.sourcePractice("paper", paperId = it)) },
            )
        }    }
    }
    }
        UiNoticeHost(
            notice,
            androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter),
            ownerKey = activeEntry?.id to qaIdentity,
            backdrop = noticeBackdrop,
        )
        chapterQa?.let { bound -> SourcePracticeChapterQa(bound,
            onClose = { chapterQa = null; chapterQaOwner = null },
            visible = activeEntry?.id == chapterQaOwner,
            onOpenPassage = { passage, source ->
                val entry = navController.currentBackStackEntry
                if (entry != null && entry.id == chapterQaOwner) {
                    if (bound.bundledBookKey != null && bound.bundledChapterKey == passage.sectionId) {
                        navController.navigate("chapter-source/${Uri.encode(bound.bundledBookKey)}/${Uri.encode(passage.sectionId)}" +
                            "?quote=${Uri.encode(passage.text)}&revision=${Uri.encode(bound.sourceRevision)}") { launchSingleTop = true }
                    } else openSource(entry, source)
                }
            }) }
    }
}

/** Builds the result callback used by a specific attempt destination. */
internal fun quizAttemptResultCallback(
    navController: NavHostController,
    entry: NavBackStackEntry,
): (String) -> Unit = { attemptId ->
    if (navController.currentBackStackEntry?.id == entry.id) {
        navController.navigate(Routes.quizResult(attemptId)) {
            popUpTo(entry.destination.id) { inclusive = true }
        }
    }
}

private data class SourceCourseResolution(val loading: Boolean = true, val binding: CourseReaderBinding? = null, val requiresCourse: Boolean = false)

private fun courseConsumed(userId: Long, epoch: Long, book: String, chapter: String, locator: String): String =
    CourseConsumedPositionCodec.encode(userId, epoch, book, chapter, locator)

/**
 * 来源卡片 → 阅读器目标（文档 + 可持久化的 Locator JSON）。
 *
 * - 有 `referenceId`：经 [SourceNavigator] 用当前用户重新核验；不是 Verified 就返回 null
 *   （不拿旧 locator 冒充可跳转，也不泄露他人来源）；
 * - 无 `referenceId`：只接受应用侧已持久化的 locator 快照（随堂测 evidence_json）。
 */
internal suspend fun resolveSourceTarget(
    container: AppContainer,
    sessionManager: SessionManager,
    source: AgentSourceUi,
): Pair<String, String?>? {
    val identity = sessionManager.identity.first()
    val userId = identity.userId ?: return null
    val referenceId = source.referenceId
    if (!referenceId.isNullOrBlank()) {
        val context = AccessContext(
            userId = userId,
            sessionEpoch = identity.epoch,
            requestId = "ui-source-${UUID.randomUUID()}",
        )
        val outcome = container.sourceNavigator.open(context, referenceId)
        if (sessionManager.identity.first() != identity) return null
        return when (outcome) {
            is SourceNavigator.Outcome.Verified ->
                outcome.documentId to runCatching { ReaderLocatorCodec.encode(outcome.locator) }.getOrNull()

            else -> null
        }
    }
    if (!source.canNavigate || source.documentId.isBlank()) return null
    val locator = source.locatorJson?.let(ReaderLocatorCodec::decodeOrNull) ?: return null
    if (locator.documentId != source.documentId) return null
    val ctx = AccessContext(userId, identity.epoch, "ui-source-${UUID.randomUUID()}")
    val verified = com.example.myapplication.data.agent.evidence.SourceVerifier(container.historicalSourceAccess)
        .verifyLocator(ctx, source.documentId, locator.contentRevision, locator, source.quote)
    if (verified !is com.example.myapplication.data.agent.evidence.VerificationOutcome.Verified) return null
    if (sessionManager.identity.first() != identity) return null
    return source.documentId to source.locatorJson
}

internal suspend fun resolveEncyclopediaSourceTarget(
    container: AppContainer,
    sessionManager: SessionManager,
    source: AgentSourceUi,
): EncyclopediaSourceLocator? {
    val identity = sessionManager.identity.first()
    val userId = identity.userId ?: return null
    val referenceId = source.referenceId
    if (!referenceId.isNullOrBlank()) {
        val context = AccessContext(
            userId = userId,
            sessionEpoch = identity.epoch,
            requestId = "ui-enc-source-${UUID.randomUUID()}",
        )
        val outcome = container.sourceNavigator.open(context, referenceId)
        if (sessionManager.identity.first() != identity) return null
        return (outcome as? SourceNavigator.Outcome.EncyclopediaVerified)?.locator
    }
    if (!source.canNavigate) return null
    val locator = EncyclopediaSourceLocator.decode(source.locatorJson) ?: return null
    val snapshot = container.encyclopediaPackRepository.currentSnapshot
        ?: (container.encyclopediaPackRepository.loadIfNeeded() as? EncyclopediaLoadState.Ready)?.snapshot
    val checked = EncyclopediaEvidenceVerifier.verify(snapshot, locator, locator.contentRevision, source.quote)
    if (sessionManager.identity.first() != identity) return null
    return (checked as? EncyclopediaVerificationResult.Verified)?.extract?.locator
}
