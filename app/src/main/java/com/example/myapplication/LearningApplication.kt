package com.example.myapplication

import android.app.Application
import com.example.myapplication.core.AppLog
import com.example.myapplication.data.test.BuiltinOrdinaryQuestionBank
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

open class LearningApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(this)
        container = AppContainer(this)
        val bankGate = BuiltinOrdinaryQuestionBank.registerStartup(container.database)
        container.applicationScope.launch(Dispatchers.IO) {
            // 启动恢复全部是「尽力而为」：单步失败不能阻断其它恢复，但**不能静默**。
            // 统一经 AppLog 落一条可过滤的诊断（不含任何密文/正文），否则故障现场无从排查。
            // 保持原有顺序执行：seed → ordinary-bank → import → index → agent → quiz → secret。
            suspend fun step(area: String, block: suspend () -> Unit) {
                runCatching { block() }
                    .onFailure { AppLog.swallowed("startup/$area", "启动恢复失败", it) }
            }
            step("seed") { container.builtInContentSeeder.seedIfNeeded() }
            try {
                step("ordinary-bank") { BuiltinOrdinaryQuestionBank.importOnStartup(container.database, assets) }
            } finally {
                bankGate.complete(Unit)
            }
            step("import") { container.importRecovery.recover() }
            step("backup") { container.dataManagementService }
            // 索引账本恢复：启动时本进程尚无活跃 worker，回收全部非终态租约。
            step("index") { container.indexProcessor.recover() }
            // Agent 运行恢复：本进程无活跃 run，遗留 RUNNING 标 INTERRUPTED（不自动重放写工具）。
            step("agent") { container.runCoordinator.recoverOnStartup() }
            // 随堂测出卷恢复（P1-2）：被杀进程遗留的 GENERATING/VALIDATING 试卷收敛为 INTERRUPTED，
            // 否则这些试卷会永久停在生成态。不重放、不重复扣费。
            step("quiz") { container.quizGenerationCoordinator.recoverInterruptedForAllUsers() }
            // secret 引用扫描收敛：保留 profile/活跃 run 引用，回收其余密文。
            step("secret") { container.profileRuntime.convergeSecrets() }
        }
    }
}
