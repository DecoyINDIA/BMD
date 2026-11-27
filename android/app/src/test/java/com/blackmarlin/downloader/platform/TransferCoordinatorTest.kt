package com.blackmarlin.downloader.platform

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.test.core.app.ApplicationProvider
import com.blackmarlin.downloader.BmdGraph
import com.blackmarlin.downloader.data.TaskEntity
import com.blackmarlin.downloader.ui.NetworkPolicy
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TransferCoordinatorTest {
    private lateinit var context: Context
    private lateinit var graph: BmdGraph
    private lateinit var networks: FakeNetworkGateway
    private lateinit var coordinator: TransferCoordinator

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        File(context.noBackupFilesDir, "transfers").deleteRecursively()
        context.noBackupFilesDir.mkdirs()
        context.deleteDatabase("bmd-tasks.db")
        graph = BmdGraph(context)
        graph.ensureReady()
        val network = context.getSystemService(ConnectivityManager::class.java).activeNetwork
            ?: ShadowNetwork.newInstance(100)
        networks = FakeNetworkGateway(network)
        coordinator = TransferCoordinator(graph, networks) { 10L * 1024 * 1024 * 1024 }
    }

    @After
    fun tearDown() {
        graph.scope.cancel()
        graph.db.close()
        context.deleteDatabase("bmd-tasks.db")
        File(context.noBackupFilesDir, "transfers").deleteRecursively()
    }

    @Test
    fun api33ForegroundServiceStartNotAllowedHoldsWaitingAndroidInsteadOfFailing() = runBlocking {
        val task = sampleTask("task-fgs-refused", 10_001)
        graph.db.tasks().insert(task)

        val outcome = coordinator.runNetwork(task.id, task.scheduleEpoch, null, false, { false }) {
            throw ForegroundServiceStartNotAllowedException("startForeground not allowed from background")
        }

        assertFalse(outcome.retry)
        assertFalse(outcome.localReady)
        assertTrue(networks.released)
        assertFalse(coordinator.hasOwner(task.id))
        val updated = graph.db.tasks().task(task.id)!!
        assertEquals("WAITING_ANDROID", updated.stage)
        assertNull(updated.errorCode)
        assertNull(updated.ownerSession)
        assertFalse(updated.userResumeRequired)
    }

    @Test
    fun api33ForegroundIllegalStateHoldsWaitingAndroidInsteadOfFailing() = runBlocking {
        val task = sampleTask("task-fgs-ise", 10_002)
        graph.db.tasks().insert(task)

        val outcome = coordinator.runNetwork(task.id, task.scheduleEpoch, null, false, { false }) {
            throw IllegalStateException("Foreground service start blocked")
        }

        assertFalse(outcome.retry)
        assertFalse(outcome.localReady)
        assertTrue(networks.released)
        val updated = graph.db.tasks().task(task.id)!!
        assertEquals("WAITING_ANDROID", updated.stage)
        assertNull(updated.errorCode)
        assertNull(updated.ownerSession)
    }

    @Test
    fun api33SchedulerHoldsWaitingAndroidWhenNotVisible() = runBlocking {
        val task = sampleTask("task-bg-schedule", 10_003)
        graph.db.tasks().insert(task)
        assertFalse(graph.visibility.visible)

        graph.scheduler.schedule(task, fromVisibleAction = false)

        val updated = graph.db.tasks().task(task.id)!!
        assertEquals("WAITING_ANDROID", updated.stage)
        assertNull(updated.ownerSession)
    }

    @Test
    fun unexpectedNonForegroundFailureStillMarksTaskFailed() = runBlocking {
        val task = sampleTask("task-other-error", 10_004)
        graph.db.tasks().insert(task)

        val outcome = coordinator.runNetwork(task.id, task.scheduleEpoch, null, false, { false }) {
            throw IllegalArgumentException("unexpected failure")
        }

        assertFalse(outcome.retry)
        val updated = graph.db.tasks().task(task.id)!!
        assertEquals("FAILED", updated.stage)
        assertEquals("TRANSFER_INTERRUPTED", updated.errorCode)
        assertNull(updated.ownerSession)
    }

    private fun sampleTask(id: String, jobId: Int) = TaskEntity(
        id = id,
        submissionId = "sub-$id",
        name = "archive.zip",
        sourceUrl = "https://example.test/archive.zip",
        origin = "https://example.test",
        jobId = jobId,
        stage = "QUEUED",
        userIntent = "RUN",
        scheduleEpoch = 1L,
    )

    private class FakeNetworkGateway(private val network: Network) : NetworkGateway {
        var released = false
        override fun select(policy: NetworkPolicy, allowMetered: Boolean): Network = network
        override fun admit(network: Network, policy: NetworkPolicy, allowMetered: Boolean): NetworkLease =
            NetworkLease(network, 1L, policy, allowMetered)
        override fun release(lease: NetworkLease) {
            released = true
            lease.valid.set(false)
        }
        override fun metered(network: Network): Boolean = false
        override fun eligible(network: Network, policy: NetworkPolicy, allowMetered: Boolean): Boolean = true
    }
}
