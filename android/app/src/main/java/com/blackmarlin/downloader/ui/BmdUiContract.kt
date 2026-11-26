package com.blackmarlin.downloader.ui

import kotlinx.coroutines.flow.StateFlow

/** UI boundary. Implementations persist commands before acknowledging them. */
interface BmdUiStore {
    val state: StateFlow<BmdAppState>
    suspend fun inspectLink(request: InspectLinkRequest): LinkReview
    suspend fun execute(command: BmdCommand): BmdResult
    suspend fun diagnostics(): String
}

interface BmdUiStoreOwner { val uiStore: BmdUiStore }

data class BmdAppState(
    val tasks: List<UiTask> = emptyList(),
    val files: List<UiFile> = emptyList(),
    val settings: UiSettings = UiSettings(),
    val storage: UiStorage = UiStorage(),
    val credentials: List<UiCredentialSummary> = emptyList(),
    val loading: Boolean = true,
    val admissionNotice: String? = null,
    val notificationPermissionGranted: Boolean = true,
    val transferChannelEnabled: Boolean = true,
    val completionChannelEnabled: Boolean = true,
    val versionName: String = "",
    val engineRevision: String = "",
)

enum class TransferStage {
    QUEUED, CONNECTING, DOWNLOADING, PAUSING, PAUSED, WAITING_NETWORK,
    WAITING_SPACE, WAITING_ANDROID, INTERRUPTED, VERIFYING, WAITING_VERIFY,
    SAVING, WAITING_SAVE, NEEDS_LINK, NEEDS_CREDENTIALS, FILE_CHANGED,
    FAILED, COMPLETE, CANCELING, CANCELED,
}

enum class TaskOperation { PAUSE, RESUME, CANCEL, RETRY, RETRY_SAVE, RESTART, REPLACE_LINK, STOP_SAVING_KEEP_DATA, SAVE_SEPARATE_COPY, DISCARD_DOWNLOADED_DATA }
enum class NetworkPolicy { UNMETERED, ANY }
enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class CredentialKind { NONE, BASIC, BEARER }
enum class FileSort { NEWEST, OLDEST, NAME, LARGEST }
enum class FileKind { ALL, DOCUMENTS, IMAGES, VIDEO, AUDIO, ARCHIVES, OTHER }
enum class DestinationKind { PUBLIC_DOWNLOADS, DOCUMENT_TREE }

data class UiDestination(
    val kind: DestinationKind = DestinationKind.PUBLIC_DOWNLOADS,
    val displayName: String = "Downloads",
    val treeUri: String? = null,
    val available: Boolean = true,
    val freeBytes: Long? = null,
)

data class UiTask(
    val id: String,
    val name: String,
    /** Parsed scheme/host/effective port only; never a signed URL or credentials. */
    val origin: String,
    val stage: TransferStage,
    val bytesReceived: Long = 0,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val secondsRemaining: Long? = null,
    val saveBytes: Long? = null,
    val saveTotalBytes: Long? = null,
    val mimeType: String? = null,
    val destination: UiDestination = UiDestination(),
    val networkPolicy: NetworkPolicy = NetworkPolicy.UNMETERED,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val detail: String? = null,
    val errorCode: String? = null,
    val permittedActions: Set<TaskOperation> = emptySet(),
    val publishedFileId: String? = null,
    val publicationUncertain: Boolean = false,
    val expectedSha256: String? = null,
    val sha256Verified: Boolean = false,
    val savedBeforeCancellation: Boolean = false,
    val diagnostics: String? = null,
    val runGeneration: Long = 0,
)

data class UiFile(
    val id: String,
    val taskId: String?,
    val name: String,
    val mimeType: String,
    val bytes: Long,
    val savedAt: Long,
    val destinationName: String,
    /** Content URI with app-owned read permission. Never expose private file paths. */
    val contentUri: String,
    val accessible: Boolean = true,
    val sha256Verified: Boolean = false,
)

data class UiSettings(
    val defaultNetwork: NetworkPolicy = NetworkPolicy.UNMETERED,
    val maxActiveTransfers: Int = 2,
    val segmentsPerTransfer: Int = 2,
    val globalSegmentLimit: Int = 4,
    val perHostLimit: Int = 2,
    /** Zero means unlimited. */
    val speedLimitBytesPerSecond: Long = 0,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val reducedMotion: Boolean = false,
    val privateNotifications: Boolean = true,
    val defaultDestination: UiDestination = UiDestination(),
)

data class UiStorage(
    val stagedBytes: Long = 0,
    val publishedBytes: Long = 0,
    val retainedPartialBytes: Long = 0,
    val availableBytes: Long? = null,
    /** Only settled tasks whose data may safely be explicitly removed. */
    val cleanupCandidates: List<UiCleanupItem> = emptyList(),
)
data class UiCleanupItem(val taskId: String, val name: String, val bytes: Long, val reason: String)
data class UiCredentialSummary(val origin: String, val kind: CredentialKind)

/** Secrets are transient input, never serialized by the UI or included in diagnostics. */
data class UiCredentialInput(
    val origin: String,
    val kind: CredentialKind,
    val username: String = "",
    val secret: String = "",
    val rememberForOrigin: Boolean = false,
) {
    override fun toString(): String = "UiCredentialInput(origin=$origin, kind=$kind, username=[redacted], secret=[redacted], rememberForOrigin=$rememberForOrigin)"
}

data class InspectLinkRequest(
    val url: String,
    val networkPolicy: NetworkPolicy,
    val allowMeteredOnce: Boolean = false,
    val allowPlainHttp: Boolean = false,
    val credential: UiCredentialInput? = null,
) {
    override fun toString(): String = "InspectLinkRequest(url=[redacted], networkPolicy=$networkPolicy, allowMeteredOnce=$allowMeteredOnce, allowPlainHttp=$allowPlainHttp, credential=$credential)"
}

data class LinkReview(
    val url: String,
    val origin: String,
    val suggestedName: String,
    val mimeType: String? = null,
    val totalBytes: Long? = null,
    val supportsResume: Boolean? = null,
    val warning: String? = null,
    val existingTaskId: String? = null,
    val requiresCredentials: Boolean = false,
    val possibleHtmlPage: Boolean = false,
) {
    override fun toString(): String = "LinkReview(url=[redacted], origin=$origin, suggestedName=$suggestedName, mimeType=$mimeType, totalBytes=$totalBytes, supportsResume=$supportsResume, warning=$warning, existingTaskId=$existingTaskId, requiresCredentials=$requiresCredentials, possibleHtmlPage=$possibleHtmlPage)"
}

data class StartDownloadRequest(
    val submissionId: String,
    val review: LinkReview,
    val fileName: String,
    val destination: UiDestination,
    val networkPolicy: NetworkPolicy,
    val expectedSha256: String? = null,
    val credential: UiCredentialInput? = null,
    val allowMeteredOnce: Boolean = false,
    val allowPlainHttp: Boolean = false,
    /** Explicit user choice after duplicate detection; implementation still avoids overwrite. */
    val saveAnotherCopy: Boolean = false,
    val destinationGrantFlags: Int = 0,
)

sealed interface BmdCommand {
    /** Refresh permissions/file availability only. Must not clear a user's stop or pause intent. */
    data object RefreshSystemState : BmdCommand
    data class StartDownload(val request: StartDownloadRequest) : BmdCommand
    data class TaskAction(
        val taskId: String,
        val operation: TaskOperation,
        val replacementUrl: String? = null,
        val allowPlainHttp: Boolean = false,
        val allowMeteredOnce: Boolean = false,
        val expectedGeneration: Long? = null,
        val replacementRequest: StartDownloadRequest? = null,
        val expectedScheduleEpoch: Long? = null,
    ) : BmdCommand {
        override fun toString(): String = "TaskAction(taskId=$taskId, operation=$operation, replacementUrl=[redacted], allowPlainHttp=$allowPlainHttp, allowMeteredOnce=$allowMeteredOnce, expectedGeneration=$expectedGeneration, replacementRequest=$replacementRequest)"
    }
    /** null taskId changes the default destination. */
    data class ChangeDestination(val taskId: String?, val destination: UiDestination, val receivedGrantFlags: Int = 0) : BmdCommand
    /** Compare candidate bytes with retained staging; a name or length match is not enough. */
    data class LocateSavedFile(val taskId: String, val candidateUri: String, val receivedGrantFlags: Int = 0) : BmdCommand
    /** null taskId stores an opted-in remembered credential; otherwise persists for this task. */
    data class SaveCredential(val taskId: String?, val credential: UiCredentialInput) : BmdCommand
    data class ForgetCredential(val origin: String) : BmdCommand
    data class UpdateSettings(val settings: UiSettings) : BmdCommand
    data class RemoveFiles(val fileIds: Set<String>, val deleteFromDevice: Boolean) : BmdCommand
    data class RemoveTransfers(val taskIds: Set<String>) : BmdCommand
    data class CleanupPartials(val taskIds: Set<String>) : BmdCommand
}

data class BmdResult(
    val taskId: String? = null,
    val message: String? = null,
    /** Item ID -> redacted, user-facing failure; successful items remain successful. */
    val failedItems: Map<String, String> = emptyMap(),
)

/** A safe, actionable failure from a store. Raw request errors must be redacted upstream. */
class BmdUiException(val userMessage: String, val recovery: Recovery = Recovery.NONE) : Exception(userMessage) {
    enum class Recovery { NONE, METERED_CONSENT, SOURCE_CREDENTIALS, CHOOSE_DESTINATION, WAITING_ANDROID }
}
