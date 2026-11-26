package com.blackmarlin.downloader.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BmdViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    private lateinit var backend: FakeUiStore
    private lateinit var model: BmdViewModel
    private lateinit var savedState: SavedStateHandle

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        backend = FakeUiStore()
        savedState = SavedStateHandle()
        model = BmdViewModel(backend, savedState)
        models.put("model", model)
        model.addLink()
    }
    @After fun teardown() { models.clear(); Dispatchers.resetMain() }

    @Test fun noHttpProbeOccursBeforeExplicitTaskConsent() = runTest {
        model.setDraftUrl("http://192.168.1.3/archive.zip")
        model.reviewLink()
        runCurrent()
        assertTrue(model.session.value.dialog is BmdDialog.PlainHttp)
        assertTrue(backend.probes.isEmpty())
        model.allowPlainHttp()
        runCurrent()
        assertEquals(1, backend.probes.size)
        assertTrue(backend.probes.single().allowPlainHttp)
    }

    @Test fun dismissingHttpConsentSendsNoRequest() = runTest {
        model.setDraftUrl("http://example.test/file")
        model.reviewLink(); model.dismissDialog(); runCurrent()
        assertTrue(backend.probes.isEmpty())
        assertFalse(model.session.value.draft.allowPlainHttp)
    }

    @Test fun staleProbeCannotOverwriteNewSharedDraftOrNavigateAway() = runTest {
        val suspended = CompletableDeferred<LinkReview>()
        backend.probeOverride = { suspended.await() }
        model.setDraftUrl("https://old.example.test/old.zip")
        model.reviewLink(); runCurrent()
        model.incomingShare("https://new.example.test/new.zip")
        val newSubmission = model.session.value.draft.submissionId
        suspended.complete(LinkReview("https://old.example.test/old.zip", "https://old.example.test", "old.zip"))
        runCurrent()
        assertEquals("https://new.example.test/new.zip", model.session.value.draft.url)
        assertEquals(newSubmission, model.session.value.draft.submissionId)
        assertNull(model.session.value.draft.review)
        assertEquals(BmdScreen.ADD_LINK, model.session.value.route.screen)
    }

    @Test fun newDraftDoesNotInheritOneTaskMeteredConsent() = runTest {
        backend.requireMeteredConsent = true
        model.setDraftUrl("https://example.test/one.zip")
        model.reviewLink(); runCurrent()
        assertEquals(BmdDialog.Metered, model.session.value.dialog)
        model.allowMeteredOnce(); runCurrent()
        assertTrue(backend.probes.last().allowMeteredOnce)
        model.setDraftUrl("https://example.test/two.zip")
        assertFalse(model.session.value.draft.allowMeteredOnce)
    }

    @Test fun newShareDismissesOldConsentWithoutAdmittingNewLink() = runTest {
        model.setDraftUrl("http://old.example.test/old.zip")
        model.reviewLink()
        model.incomingShare("http://new.example.test/new.zip")
        model.allowPlainHttp() // A stale UI callback must have no pending admission to run.
        runCurrent()
        assertTrue(backend.probes.isEmpty())
        assertFalse(model.session.value.draft.allowPlainHttp)
    }

    @Test fun ordinaryShareIsNotAReplacementForAnEarlierTask() = runTest {
        backend.state.value = backend.state.value.copy(tasks = listOf(UiTask("existing", "old.zip", "https://example.test", TransferStage.NEEDS_LINK)))
        model.taskAction("existing", TaskOperation.REPLACE_LINK)
        assertEquals("existing", model.session.value.draft.replacementTaskId)
        model.incomingShare("https://other.example.test/fresh.zip")
        assertNull(model.session.value.draft.replacementTaskId)
    }

    @Test fun duplicateCopyRetryKeepsDurableSubmissionIdAfterAcknowledgementFailure() = runTest {
        backend.existingTaskId = "already-downloaded"
        model.setDraftUrl("https://example.test/file.zip")
        model.reviewLink(); runCurrent()
        val submission = model.session.value.draft.submissionId
        backend.failNextStart = true
        model.startDownload(saveAnother = true); runCurrent()
        model.startDownload(saveAnother = true); runCurrent()
        val starts = backend.commands.filterIsInstance<BmdCommand.StartDownload>()
        assertEquals(2, starts.size)
        assertEquals(listOf(submission, submission), starts.map { it.request.submissionId })
    }

    @Test fun secretsAreNeverWrittenIntoSavedStateAndDtoStringsRedactThem() = runTest {
        model.setDraftUrl("https://files.example.test/archive.zip?secret=signed")
        val input = UiCredentialInput("https://files.example.test", CredentialKind.BEARER, secret = "TOP-SECRET")
        model.openCredentials(input.origin)
        model.updateCredentialForm(input)
        assertFalse(savedState.keys().any { it.contains("credential", true) || it.contains("secret", true) || it.contains("password", true) })
        assertFalse(input.toString().contains("TOP-SECRET"))
        assertFalse(InspectLinkRequest(model.session.value.draft.url, NetworkPolicy.UNMETERED, credential = input).toString().contains("signed"))
        assertFalse(BmdCommand.TaskAction("id", TaskOperation.REPLACE_LINK, "https://x.test/?token=signed").toString().contains("signed"))
    }

    @Test fun replacementConfirmationCarriesEditedReviewAndExactScopedCredential() = runTest {
        backend.state.value = backend.state.value.copy(tasks = listOf(UiTask("existing", "old.zip", "https://old.example.test",
            TransferStage.NEEDS_LINK, expectedSha256 = "a".repeat(64), runGeneration = 9)))
        model.taskAction("existing", TaskOperation.REPLACE_LINK)
        model.setDraftUrl("https://new.example.test/new.zip")
        assertEquals("a".repeat(64), model.session.value.draft.expectedSha256)
        model.reviewLink(); runCurrent()
        model.setDraftName("renamed.zip")
        model.setExpectedSha("b".repeat(64))
        model.setDraftNetwork(NetworkPolicy.ANY)
        model.destination()
        val destination = UiDestination(DestinationKind.DOCUMENT_TREE, "Chosen folder", "content://provider/tree/chosen")
        model.destinationChosen("draft", destination, flags = 3)
        val credential = UiCredentialInput("https://new.example.test", CredentialKind.BEARER, secret = "NEW-TASK-TOKEN")
        model.openCredentials(credential.origin)
        model.saveCredential(credential); runCurrent()
        model.startDownload()
        val confirmation = model.session.value.dialog as BmdDialog.RestartTask
        model.confirmRestart(confirmation); runCurrent()
        val command = backend.commands.filterIsInstance<BmdCommand.TaskAction>().last()
        val request = command.replacementRequest!!
        assertEquals(TaskOperation.REPLACE_LINK, command.operation)
        assertEquals(9L, command.expectedGeneration)
        assertEquals("renamed.zip", request.fileName)
        assertEquals("b".repeat(64), request.expectedSha256)
        assertEquals(NetworkPolicy.ANY, request.networkPolicy)
        assertEquals(destination, request.destination)
        assertEquals(3, request.destinationGrantFlags)
        assertEquals(credential, request.credential)
        assertEquals("https://new.example.test/new.zip", request.review.url)
        assertFalse(command.toString().contains("NEW-TASK-TOKEN"))
    }
}

private class FakeUiStore : BmdUiStore {
    override val state = MutableStateFlow(BmdAppState(loading = false))
    val probes = mutableListOf<InspectLinkRequest>()
    val commands = mutableListOf<BmdCommand>()
    var probeOverride: (suspend (InspectLinkRequest) -> LinkReview)? = null
    var requireMeteredConsent = false
    var existingTaskId: String? = null
    var failNextStart = false
    override suspend fun inspectLink(request: InspectLinkRequest): LinkReview {
        probes += request
        probeOverride?.let { return it(request) }
        if (requireMeteredConsent && !request.allowMeteredOnce) throw BmdUiException("Metered network", BmdUiException.Recovery.METERED_CONSENT)
        return LinkReview(request.url, parseSourceOrigin(request.url)!!, "file.zip", existingTaskId = existingTaskId)
    }
    override suspend fun execute(command: BmdCommand): BmdResult {
        commands += command
        if (command is BmdCommand.StartDownload) {
            if (failNextStart) { failNextStart = false; throw BmdUiException("Acknowledgement interrupted") }
            return BmdResult(taskId = "new-task")
        }
        return BmdResult()
    }
    override suspend fun diagnostics() = "Version: test; no secrets"
}
