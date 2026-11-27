package com.blackmarlin.downloader.storage

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.blackmarlin.downloader.data.BmdDatabase
import com.blackmarlin.downloader.data.PublicationEntity
import com.blackmarlin.downloader.data.TaskEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Faults are injected at provider boundaries, while the real Room journal and manager run. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PublicationManagerTest {
    private lateinit var context: Context
    private lateinit var db: BmdDatabase
    private lateinit var gate: Mutex
    private lateinit var io: FakeProvider
    private lateinit var manager: PublicationManager
    private lateinit var task: TaskEntity
    private lateinit var stage: File
    private val payload = ByteArray(196_613) { ((it * 31) % 251).toByte() }

    @Before fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BmdDatabase::class.java).allowMainThreadQueries().build()
        gate = Mutex()
        io = FakeProvider()
        manager = PublicationManager(context, db, gate, io)
        task = TaskEntity(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "archive.zip", "https://example.test/archive.zip",
            "https://example.test", 42, totalBytes = payload.size.toLong(), stage = "SAVING", verified = true,
            runGeneration = 7, ownerSession = "owner-7", receivedBytes = payload.size.toLong())
        db.tasks().insert(task)
        stage = File(context.noBackupFilesDir, "transfers/${task.id}/verified.payload")
        stage.parentFile!!.mkdirs()
        stage.writeBytes(payload)
    }

    @After fun tearDown() { db.close(); stage.parentFile?.deleteRecursively() }

    @Test fun successfulMediaStoreCommitAtomicallyIndexesFileAndRetainsStageForCoordinatorCleanup() = runBlocking {
        val outcome = save()
        assertTrue(outcome.saved)
        assertEquals("PUBLISHED", db.tasks().publication(task.id)!!.phase)
        assertTrue(db.tasks().publication(task.id)!!.commitPlanned)
        assertEquals("COMPLETE", db.tasks().task(task.id)!!.stage)
        assertEquals(1, db.tasks().files().size)
        assertArrayEquals(payload, io.documents.values.single())
        assertTrue(stage.exists())
        assertEquals(1, io.creates)
    }

    @Test fun mediaStoreDoesNotReadBeforeItsBackingFileHasBeenOpenedForWriting() = runBlocking {
        io.noReadableBackingFileUntilWrite = true
        assertTrue(save().saved)
        assertEquals(0, io.prematureReads)
        assertEquals(1, io.creates)
        assertEquals(0, io.truncates)
        assertArrayEquals(payload, io.documents.values.single())
    }

    @Test fun failedFirstWriteOpenRetriesTheSameRecordedEmptyMediaStoreRow() = runBlocking {
        io.noReadableBackingFileUntilWrite = true
        io.failWriteOpenOnce = true
        assertFalse(save().saved)
        val retained = db.tasks().publication(task.id)!!
        assertEquals("URI_RECORDED", retained.phase)
        assertEquals(0L, retained.copiedBytes)
        assertNotNull(retained.uri)
        assertTrue(stage.exists())
        assertTrue(save().saved)
        assertEquals(retained.uri, db.tasks().fileForTask(task.id)!!.uri)
        assertEquals(1, io.creates)
        assertEquals(0, io.prematureReads)
        assertEquals(0, io.truncates)
    }

    @Test fun safeFailureDiagnosticsExcludeProviderMessagesAndPaths() {
        val failure = IOException("https://private.example/file?token=secret-value",
            ErrnoException("/private/provider/account-secret", OsConstants.ENOSPC))
        val diagnostic = publicationFailureDiagnostic(PublicationOperation.OPEN_WRITE, "COPYING", "MEDIASTORE", failure)
        assertTrue(diagnostic.contains("phase=COPYING"))
        assertTrue(diagnostic.contains("IOException,ErrnoException"))
        assertTrue(diagnostic.contains("errno=${OsConstants.ENOSPC}"))
        assertFalse(diagnostic.contains("secret"))
        assertFalse(diagnostic.contains("private"))
        assertFalse(diagnostic.contains("https"))
        assertFalse(diagnostic.contains("/"))
    }

    @Test fun firstSequentialSafWriteCanSucceedWithoutSeekOrRename() = runBlocking {
        setTask(task.copy(destinationKind = "DOCUMENT_TREE", destinationUri = "content://provider/tree/downloads"))
        io.seekable = false
        assertTrue(save().saved)
        assertEquals(0, io.truncates)
        assertArrayEquals(payload, io.documents.values.single())
    }

    @Test fun unsupportedProviderReadKeepsCreatedUriAndPrivateBytesForExplicitRecovery() = runBlocking {
        setTask(task.copy(destinationKind = "DOCUMENT_TREE", destinationUri = "content://provider/tree/downloads"))
        io.unsupportedStreamOnRead = true
        val outcome = save()
        assertFalse(outcome.saved)
        assertTrue(outcome.message.orEmpty().contains("local device folder"))
        val attempt = db.tasks().publication(task.id)!!
        assertEquals(io.documents.keys.single(), attempt.uri)
        assertEquals("UNRESOLVED", attempt.phase)
        assertEquals("WAITING_SAVE", db.tasks().task(task.id)!!.stage)
        assertEquals(1, io.creates)
        assertEquals(0, io.truncates)
        assertEquals(0, io.finalizes)
        assertTrue(db.tasks().files().isEmpty())
        assertArrayEquals(payload, stage.readBytes())
        assertFalse(save().saved)
        assertEquals("A retry must not create another provider document", 1, io.creates)
    }

    @Test fun unsupportedProviderWriteKeepsKnownUriWithoutCopyOrPublication() = runBlocking {
        setTask(task.copy(destinationKind = "DOCUMENT_TREE", destinationUri = "content://provider/tree/downloads"))
        io.unsupportedStreamOnWrite = true
        assertFalse(save().saved)
        val attempt = db.tasks().publication(task.id)!!
        assertEquals(io.documents.keys.single(), attempt.uri)
        assertEquals("UNRESOLVED", attempt.phase)
        assertFalse(attempt.commitPlanned)
        assertEquals(0L, attempt.copiedBytes)
        assertTrue(io.documents.values.single().isEmpty())
        assertTrue(db.tasks().files().isEmpty())
        assertArrayEquals(payload, stage.readBytes())
    }

    @Test fun safLostCreateResultDoesNotCreateAgainOnRetry() = runBlocking {
        setTask(task.copy(destinationKind = "DOCUMENT_TREE", destinationUri = "content://provider/tree/downloads"))
        io.throwAfterCreate = true
        assertFalse(save().saved)
        assertNull(db.tasks().publication(task.id)!!.uri)
        assertEquals("UNRESOLVED", db.tasks().publication(task.id)!!.phase)
        io.throwAfterCreate = false
        assertFalse(save().saved)
        assertEquals(1, io.creates)
        assertTrue(db.tasks().files().isEmpty())
        assertTrue(stage.exists())
    }

    @Test fun mediaStoreLostCreateReattachesOnlyExactOwnedEmptyAttempt() = runBlocking {
        io.throwAfterCreate = true
        assertFalse(save().saved)
        io.throwAfterCreate = false
        assertTrue(save().saved)
        assertEquals(1, io.creates)
        assertEquals(1, io.recoveries)
    }

    @Test fun cancellationBeforeCommitPreventsPublicationAndKeepsComparisonBytes() = runBlocking {
        var stopped = false
        io.onWrite = {
            if (!stopped) {
                gate.withLock { db.tasks().update(db.tasks().task(task.id)!!.copy(userIntent = "CANCEL")) }
                stopped = true
            }
        }
        assertFalse(manager.save(task.id, 7, "owner-7", { stopped }, {}).saved)
        assertFalse(db.tasks().publication(task.id)!!.commitPlanned)
        assertEquals(0, io.finalizes)
        assertTrue(db.tasks().files().isEmpty())
        assertTrue(stage.exists())
        assertTrue(io.aborts > 0)
    }

    @Test fun cancellationImmediatelyAfterCommitBarrierPreservesProvenSavedOutput() = runBlocking {
        var stopped = false
        io.onClose = {
            runBlocking {
                assertTrue(db.tasks().publication(task.id)!!.commitPlanned)
                gate.withLock { db.tasks().update(db.tasks().task(task.id)!!.copy(userIntent = "CANCEL")) }
                stopped = true
            }
        }
        assertTrue(manager.save(task.id, 7, "owner-7", { stopped }, {}).saved)
        val saved = db.tasks().task(task.id)!!
        assertEquals("COMPLETE", saved.stage)
        assertTrue(saved.savedBeforeCancellation)
        assertEquals(1, db.tasks().files().size)
        assertTrue(stage.exists())
    }

    @Test fun externalCommitThenErrorIsReconciledWithoutSecondCreate() = runBlocking {
        io.failFinalizeAfterVisibility = true
        assertFalse(save().saved)
        assertEquals("UNRESOLVED", db.tasks().publication(task.id)!!.phase)
        assertTrue(db.tasks().publication(task.id)!!.commitPlanned)
        assertTrue(db.tasks().files().isEmpty())
        assertTrue(stage.exists())
        io.failFinalizeAfterVisibility = false
        assertTrue(save().saved)
        assertEquals(1, io.creates)
        assertEquals(1, db.tasks().files().size)
    }

    @Test fun laterVisibleRetryPreservesCancellationProvenanceAfterUncertainCommit() = runBlocking {
        io.onClose = { runBlocking {
            gate.withLock { db.tasks().update(db.tasks().task(task.id)!!.copy(userIntent = "CANCEL", cancelRequestedDuringSave = true)) }
        } }
        io.failFinalizeAfterVisibility = true
        assertFalse(save().saved)
        assertTrue(db.tasks().publication(task.id)!!.commitPlanned)
        val held = db.tasks().task(task.id)!!
        db.tasks().update(held.copy(userIntent = "RUN", runGeneration = 8, ownerSession = "owner-8"))
        io.onClose = {}
        io.failFinalizeAfterVisibility = false
        assertTrue(manager.save(task.id, 8, "owner-8", { false }, {}).saved)
        assertTrue(db.tasks().task(task.id)!!.savedBeforeCancellation)
        assertEquals("Saved before cancellation", db.tasks().task(task.id)!!.detail)
        assertEquals(1, io.creates)
    }

    @Test fun postCloseSafTruncationDoesNotBecomeComplete() = runBlocking {
        setTask(task.copy(destinationKind = "DOCUMENT_TREE", destinationUri = "content://provider/tree/downloads"))
        io.truncateOnClose = true
        assertFalse(save().saved)
        assertEquals("UNRESOLVED", db.tasks().publication(task.id)!!.phase)
        assertTrue(db.tasks().files().isEmpty())
        assertTrue(stage.exists())
    }

    @Test fun wrongSameLengthCandidateIsNeverAdoptedOrChanged() = runBlocking {
        val uri = "content://provider/document/wrong"
        val wrong = payload.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        io.documents[uri] = wrong
        val outcome = manager.locate(task.id, 7, "owner-7", uri, { false }, {})
        assertFalse(outcome.saved)
        assertTrue(db.tasks().files().isEmpty())
        assertNull(db.tasks().publication(task.id))
        assertArrayEquals(wrong, io.documents[uri])
        assertEquals(0, io.creates)
    }

    @Test fun exactUserSelectedCandidateCanBeAdoptedWithFullByteProof() = runBlocking {
        val uri = "content://provider/document/chosen"
        io.documents[uri] = payload.copyOf()
        assertTrue(manager.locate(task.id, 7, "owner-7", uri, { false }, {}).saved)
        assertEquals(uri, db.tasks().fileForTask(task.id)!!.uri)
        assertEquals("USER_SELECTED", db.tasks().publication(task.id)!!.adapter)
        assertEquals(0, io.creates)
    }

    @Test fun nonseekableExistingPartialIsNotOverwrittenOrDuplicated() = runBlocking {
        setTask(task.copy(destinationKind = "DOCUMENT_TREE", destinationUri = "content://provider/tree/downloads"))
        val uri = "content://provider/document/partial"
        val partial = payload.copyOf(50_000)
        io.documents[uri] = partial
        io.seekable = false
        db.tasks().putPublication(attempt(uri, "SAF", "COPYING", partial.size.toLong()))
        assertFalse(save().saved)
        assertArrayEquals(partial, io.documents[uri])
        assertEquals(0, io.creates)
        assertEquals(0, io.truncates)
        assertTrue(stage.exists())
    }

    @Test fun mismatchingKnownPrefixIsNotTruncated() = runBlocking {
        val uri = "content://provider/document/changed"
        val changed = ByteArray(100) { 7 }
        io.documents[uri] = changed
        db.tasks().putPublication(attempt(uri, "MEDIASTORE", "COPYING", 100))
        assertFalse(save().saved)
        assertArrayEquals(changed, io.documents[uri])
        assertEquals(0, io.truncates)
        assertEquals("UNRESOLVED", db.tasks().publication(task.id)!!.phase)
    }

    @Test fun separateCopyArchivesPreviousIdentityWithoutDeletingProviderData() = runBlocking {
        val uri = "content://provider/document/uncertain"
        io.documents[uri] = payload.copyOf()
        val original = attempt(uri, "SAF", "UNRESOLVED", payload.size.toLong())
        db.tasks().putPublication(original)
        setTask(task.copy(ownerSession = null, stage = "WAITING_SAVE"))
        manager.prepareSeparateCopy(task.id)
        assertNull(db.tasks().publication(task.id))
        assertEquals(original.attemptId, db.tasks().publicationHistory(task.id).single().attemptId)
        assertArrayEquals(payload, io.documents[uri])
        assertTrue(stage.exists())
    }

    @Test fun staleGenerationCannotCreateOrPublish() = runBlocking {
        assertFalse(manager.save(task.id, 6, "owner-6", { false }, {}).saved)
        assertEquals(0, io.creates)
        assertEquals("SAVING", db.tasks().task(task.id)!!.stage)
        assertTrue(db.tasks().files().isEmpty())
    }

    @Test fun requiredChecksumMustBeVerifiedBeforeProviderCreation() = runBlocking {
        setTask(task.copy(expectedSha256 = "a".repeat(64), sha256Verified = false))
        assertFalse(save().saved)
        assertEquals(0, io.creates)
        assertTrue(stage.exists())
    }

    @Test fun truncatedVerifiedStageCannotPublishAgainstCompletedLengthProof() = runBlocking {
        stage.writeBytes(payload.copyOf(payload.size - 1))
        assertFalse(save().saved)
        assertEquals(0, io.creates)
        assertFalse(db.tasks().task(task.id)!!.verified)
        assertEquals("LOCAL_INTEGRITY_MISMATCH", db.tasks().task(task.id)!!.errorCode)
    }

    @Test fun sameSizeMutationOfVerifiedStageIsRejectedByStreamingExpectedHash() = runBlocking {
        val expected = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        setTask(task.copy(expectedSha256 = expected, sha256Verified = true))
        stage.writeBytes(payload.copyOf().also { it[100] = (it[100] + 1).toByte() })
        assertFalse(save().saved)
        assertTrue(db.tasks().files().isEmpty())
        assertFalse(db.tasks().publication(task.id)!!.commitPlanned)
        assertFalse(db.tasks().task(task.id)!!.sha256Verified)
        assertEquals(0, io.finalizes)
    }

    @Test fun laterSaveAfterPreCommitCancelDoesNotClaimItWasSavedBeforeCancellation() = runBlocking {
        var stopped = false
        io.onWrite = {
            gate.withLock { db.tasks().update(db.tasks().task(task.id)!!.copy(userIntent = "CANCEL")) }
            stopped = true
        }
        assertFalse(manager.save(task.id, 7, "owner-7", { stopped }, {}).saved)
        assertFalse(db.tasks().publication(task.id)!!.commitPlanned)
        val held = db.tasks().task(task.id)!!
        db.tasks().update(held.copy(userIntent = "RUN", runGeneration = 8, ownerSession = "owner-8"))
        stopped = false
        io.onWrite = {}
        assertTrue(manager.save(task.id, 8, "owner-8", { stopped }, {}).saved)
        assertFalse(db.tasks().task(task.id)!!.savedBeforeCancellation)
    }

    @Test fun separateCopyClearsFailedCandidateAndHoldsUntilExplicitRetrySave() = runBlocking {
        val uri = "content://provider/document/old"
        io.documents[uri] = payload.copyOf()
        db.tasks().putPublication(attempt(uri, "SAF", "UNRESOLVED", payload.size.toLong()))
        setTask(task.copy(ownerSession = null, stage = "WAITING_SAVE", recoveryCandidateUri = "content://provider/document/wrong"))
        manager.prepareSeparateCopy(task.id, expectedGeneration = 7)
        val prepared = db.tasks().task(task.id)!!
        assertNull(prepared.recoveryCandidateUri)
        assertEquals("PAUSE", prepared.userIntent)
        assertTrue(prepared.userResumeRequired)
        assertEquals(0, io.creates)
    }

    @Test fun emptyFileIsSavedWithActualEmptyContentProof() = runBlocking {
        stage.writeBytes(byteArrayOf())
        setTask(task.copy(receivedBytes = 0, totalBytes = 0))
        assertTrue(save().saved)
        assertEquals(0L, db.tasks().fileForTask(task.id)!!.bytes)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", db.tasks().publication(task.id)!!.digest)
    }

    private suspend fun save() = manager.save(task.id, 7, "owner-7", { false }, {})
    private suspend fun setTask(value: TaskEntity) { task = value; db.tasks().update(value) }
    private fun attempt(uri: String, adapter: String, phase: String, copied: Long) = PublicationEntity(task.id,
        UUID.randomUUID().toString(), adapter, phase, uri, ".bmd-test", copied, payload.size.toLong(),
        runGeneration = 7, ownerSession = "owner-7", finalName = task.name)
}

private class FakeProvider : PublicationIo {
    val documents = linkedMapOf<String, ByteArray>()
    private val markers = mutableMapOf<String, String>()
    var creates = 0
    var recoveries = 0
    var finalizes = 0
    var truncates = 0
    var aborts = 0
    var seekable = true
    var unsupportedStreamOnRead = false
    var unsupportedStreamOnWrite = false
    var throwAfterCreate = false
    var failFinalizeAfterVisibility = false
    var truncateOnClose = false
    var noReadableBackingFileUntilWrite = false
    var failWriteOpenOnce = false
    var prematureReads = 0
    private val writeOpened = mutableSetOf<String>()
    var onWrite: suspend () -> Unit = {}
    var onClose: () -> Unit = {}

    override suspend fun create(task: TaskEntity, attempt: PublicationEntity, stop: () -> Boolean): String {
        creates++
        val uri = "content://provider/document/${attempt.attemptId}"
        documents[uri] = byteArrayOf()
        markers[attempt.marker] = uri
        if (throwAfterCreate) throw IOException("Simulated process loss after external create")
        return uri
    }
    override suspend fun recoverCreated(attempt: PublicationEntity, stop: () -> Boolean): String? {
        recoveries++
        return markers[attempt.marker]
    }
    override fun assertIdentity(attempt: PublicationEntity) { if (attempt.uri !in documents) throw IOException("Identity unavailable") }
    override suspend fun openWrite(attempt: PublicationEntity, requireSafeTruncate: Boolean, stop: () -> Boolean): PublicationWriter {
        if (failWriteOpenOnce) { failWriteOpenOnce = false; throw IOException("Injected first open failure") }
        if (unsupportedStreamOnWrite) throw UnsupportedProviderStream()
        if (requireSafeTruncate && !seekable) throw IOException("Nonseekable provider")
        if (attempt.adapter == "MEDIASTORE" && !requireSafeTruncate && documents[attempt.uri]?.isNotEmpty() == true)
            throw IOException("The same nontruncating descriptor was not empty")
        if (requireSafeTruncate) truncates++
        val uri = attempt.uri!!
        writeOpened += uri
        val stream = ByteArrayOutputStream()
        documents[uri] = byteArrayOf()
        return object : PublicationWriter {
            private var closed = false
            override suspend fun write(buffer: ByteArray, offset: Int, count: Int, stop: () -> Boolean) {
                stream.write(buffer, offset, count); documents[uri] = stream.toByteArray(); onWrite()
            }
            override fun flushDurably() = Unit
            override fun closeSuccessfully() {
                onClose()
                if (truncateOnClose) documents[uri] = documents[uri]!!.copyOf(10)
                closed = true
            }
            override fun abort() { if (!closed) { aborts++; closed = true } }
        }
    }
    override suspend fun openRead(uri: String, stop: () -> Boolean): PublicationReader {
        if (unsupportedStreamOnRead) throw UnsupportedProviderStream()
        if (noReadableBackingFileUntilWrite && uri !in writeOpened) {
            prematureReads++
            throw java.io.FileNotFoundException("Injected lazy backing file")
        }
        val bytes = documents[uri] ?: throw IOException("Document unavailable")
        var offset = 0
        return object : PublicationReader {
            override suspend fun read(buffer: ByteArray, stop: () -> Boolean): Int {
                if (offset >= bytes.size) return -1
                val count = minOf(buffer.size, bytes.size - offset)
                bytes.copyInto(buffer, 0, offset, offset + count); offset += count
                return count
            }
            override fun close() = Unit
        }
    }
    override suspend fun assertClosedLength(attempt: PublicationEntity, stop: () -> Boolean) {
        if (documents[attempt.uri]!!.size.toLong() != attempt.expectedBytes) throw IOException("Wrong length")
    }
    override fun finalizeDocument(attempt: PublicationEntity) { finalizes++; if (failFinalizeAfterVisibility) throw IOException("Lost acknowledgement after provider commit") }
    override fun metadata(uri: String) = DocumentMetadata("archive.zip", documents[uri]?.size?.toLong())
}
