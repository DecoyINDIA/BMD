package com.blackmarlin.downloader

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.work.Configuration
import com.blackmarlin.downloader.bridge.EngineBridge
import com.blackmarlin.downloader.data.*
import com.blackmarlin.downloader.domain.DownloadPolicy
import com.blackmarlin.downloader.platform.*
import com.blackmarlin.downloader.storage.PublicationManager
import com.blackmarlin.downloader.ui.BmdUiStore
import com.blackmarlin.downloader.ui.BmdUiStoreOwner
import com.blackmarlin.downloader.widget.QuickDownloadWidgets
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BmdApplication : Application(), BmdUiStoreOwner, Configuration.Provider {
    lateinit var graph: BmdGraph
        private set
    override val uiStore: BmdUiStore get() = graph.repository
    // WorkManager and direct user-initiated jobs share Android's job-ID namespace.
    // The manifest removes only WorkManager's default initializer so this range is used.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setJobSchedulerJobIdRange(TaskScheduler.FIRST_WORK_JOB_ID, TaskScheduler.LAST_WORK_JOB_ID)
            .build()
    override fun onCreate() {
        super.onCreate()
        graph = BmdGraph(this)
        registerActivityLifecycleCallbacks(graph.visibility)
        graph.scope.launch { graph.repository.initialize() }
    }
}

fun Context.bmdGraph(): BmdGraph = (applicationContext as BmdApplication).graph

/** One process owns one admission gate, shared by commands, executors and publication. */
class BmdGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val gate = Mutex()
    val visibility = ActivityVisibility()
    val session = UUID.randomUUID().toString()
    val db = BmdDatabase.open(context)
    val preferences = PreferencesStore(context)
    val credentials = CredentialVault(context)
    val engine = EngineBridge(context)
    val notifications = TransferNotifications(context)
    val networks = NetworkController(context, engine, scope)
    val publication = PublicationManager(context, db, gate)
    val coordinator by lazy { TransferCoordinator(this) }
    val scheduler by lazy { TaskScheduler(this) }
    val repository by lazy { BmdRepository(this) }
    val quickDownloads = QuickDownloadWidgets(context, repository)
    private val startup = Mutex()
    @Volatile private var ready = false

    /** Every Activity, Job, Worker and receiver crosses this barrier before admitting work. */
    suspend fun ensureReady() = startup.withLock {
        if (ready) return@withLock
        gate.withLock {
            db.withTransaction {
                for (task in db.tasks().tasks()) {
                    if (DownloadPolicy.isFinal(task.stage)) {
                        // Publication may commit immediately before process death. Final-state
                        // cleanup is idempotent and never alters an already saved provider URI.
                        val attempt = db.tasks().publication(task.id)
                        if ((task.stage == "CANCELED" || attempt?.phase in setOf("PUBLISHED", "CLEANED")) &&
                            (task.sourceUrl.isNotEmpty() || task.ownerSession != null || task.recoveryCandidateUri != null || directory(task.id).exists() || attempt?.phase == "PUBLISHED")) {
                            coordinator.cleanPrivateDirectory(task.id)
                            credentials.removeTask(task.id)
                            if (attempt != null) db.tasks().putPublication(attempt.copy(phase = "CLEANED"))
                            db.tasks().update(task.copy(ownerSession = null, sourceUrl = "", recoveryCandidateUri = null))
                        } else if (task.ownerSession != null) db.tasks().update(task.copy(ownerSession = null))
                        continue
                    }
                    val publication = db.tasks().publication(task.id)
                    val local = task.verified || task.stage in LOCAL_STAGES || publication != null ||
                        File(directory(task.id), "network-complete.json").isFile
                    // A process restart is not evidence of permission to resume. This conservative
                    // barrier also covers Android's task-manager Stop, force-stop and device boot.
                    db.tasks().update(task.copy(ownerSession = null, speed = 0,
                        stage = if (local) "WAITING_SAVE" else if (task.userIntent == "PAUSE") "PAUSED" else "INTERRUPTED",
                        userResumeRequired = true,
                        detail = if (local) "Downloaded data is retained. Open the transfer and retry saving." else "Android ended the previous session. Resume when you are ready.",
                        updatedAt = System.currentTimeMillis()))
                }
            }
        }
        credentials.removeOrphans(db.tasks().tasks().map { it.id }.toSet())
        ready = true
    }

    fun directory(taskId: String): File {
        require(taskId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        return File(File(context.noBackupFilesDir, "transfers"), taskId)
    }

    companion object {
        val LOCAL_STAGES = setOf("VERIFYING", "WAITING_VERIFY", "SAVING", "WAITING_SAVE")
    }
}
