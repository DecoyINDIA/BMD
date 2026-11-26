package com.blackmarlin.downloader.storage

import android.os.ParcelFileDescriptor
import android.net.Uri
import android.provider.MediaStore
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.blackmarlin.downloader.data.BmdDatabase
import com.blackmarlin.downloader.data.PublicationEntity
import com.blackmarlin.downloader.data.TaskEntity
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual Android descriptors at the same admission boundary used by provider I/O. */
@RunWith(AndroidJUnit4::class)
class PublicationDescriptorDeviceTest {
    /**
     * Real provider/Room boundary fixtures, independent of the mandatory Rust download tests.
     * The local bytes are declared test input, not a simulated network or native-engine result.
     */
    @Test(timeout = 120_000) @SdkSuppress(minSdkVersion = 29)
    fun mediaStoreFreshAndRecoveredRowsPublishWithoutTruncatingUnprovenBytes() = runBlocking {
        withTimeout(105_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val payload = ByteArray(8193) { ((it * 17) % 251).toByte() }
            val checksum = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
            val sentinel = "unproven-provider-content".toByteArray()
            for (window in listOf("FRESH", "CREATE_PLANNED", "URI_RECORDED", "COPYING_EMPTY", "COPYING_PARTIAL", "UNPROVEN_NONEMPTY")) {
                val db = Room.inMemoryDatabaseBuilder(context, BmdDatabase::class.java).build()
                val id = UUID.randomUUID().toString()
                val task = TaskEntity(id, id, "bmd-provider-test-$id.bin", "", "", 0,
                    totalBytes = payload.size.toLong(), expectedSha256 = checksum, stage = "SAVING",
                    verified = true, sha256Verified = true, runGeneration = 1, ownerSession = "provider-test")
                val stage = File(context.noBackupFilesDir, "transfers/$id/verified.payload")
                var createdUri: String? = null
                try {
                    stage.parentFile!!.mkdirs()
                    stage.writeBytes(payload)
                    db.tasks().insert(task)
                    val io = AndroidPublicationIo(context)
                    if (window != "FRESH") {
                        val planned = PublicationEntity(id, id, "MEDIASTORE", "CREATE_PLANNED",
                            marker = ".bmd-$id", expectedBytes = payload.size.toLong(), finalName = task.name,
                            runGeneration = 1, ownerSession = "provider-test")
                        db.tasks().putPublication(planned)
                        createdUri = io.create(task, planned) { false }
                        val recorded = planned.copy(uri = createdUri, phase = "URI_RECORDED")
                        if (window in setOf("COPYING_EMPTY", "COPYING_PARTIAL", "UNPROVEN_NONEMPTY")) {
                            // Materialization occurs before COPYING is durable. Simulate a crash
                            // immediately before the first byte, or after only a matching prefix.
                            val writer = io.openWrite(recorded, requireSafeTruncate = false) { false }
                            try {
                                val bytes = when (window) {
                                    "COPYING_PARTIAL" -> payload.copyOf(513)
                                    "UNPROVEN_NONEMPTY" -> sentinel
                                    else -> byteArrayOf()
                                }
                                if (bytes.isNotEmpty()) writer.write(bytes, 0, bytes.size) { false }
                                writer.flushDurably()
                                writer.closeSuccessfully()
                                // Pending metadata may be stale. The production check must inspect
                                // the closed file descriptor's actual size before visibility changes.
                                io.assertClosedLength(recorded.copy(expectedBytes = bytes.size.toLong())) { false }
                                context.contentResolver.query(Uri.parse(createdUri), arrayOf(MediaStore.MediaColumns.IS_PENDING), null, null, null)!!.use {
                                    assertTrue(it.moveToFirst())
                                    assertEquals("The length check must precede publication", 1, it.getInt(0))
                                }
                            } finally { writer.abort() }
                        }
                        db.tasks().putPublication(when (window) {
                            "CREATE_PLANNED" -> planned // Provider returned a URI, then process lost it.
                            "COPYING_EMPTY", "COPYING_PARTIAL" -> recorded.copy(phase = "COPYING")
                            else -> recorded
                        })
                    }

                    val result = PublicationManager(context, db, Mutex()).save(id, 1, "provider-test", { false }, {})
                    val publication = requireNotNull(db.tasks().publication(id))
                    val outputUri = requireNotNull(publication.uri)
                    if (createdUri != null) assertEquals("$window must reuse its exact row", createdUri, outputUri)
                    createdUri = outputUri
                    val actual = context.contentResolver.openInputStream(Uri.parse(outputUri))!!.use { it.readBytes() }
                    if (window == "UNPROVEN_NONEMPTY") {
                        assertFalse("An unproven nonempty first writer must be held", result.saved)
                        assertArrayEquals("The nontruncating check must preserve every sentinel byte", sentinel, actual)
                        assertArrayEquals(payload, stage.readBytes())
                        assertTrue(db.tasks().files().isEmpty())
                        assertFalse(publication.commitPlanned)
                    } else {
                        assertTrue("$window: ${result.message}; phase=${publication.phase}", result.saved)
                        assertEquals("PUBLISHED", publication.phase)
                        assertEquals("COMPLETE", db.tasks().task(id)!!.stage)
                        assertEquals(outputUri, db.tasks().fileForTask(id)!!.uri)
                        assertArrayEquals("$window must publish exact bytes", payload, actual)
                        assertEquals(checksum, MessageDigest.getInstance("SHA-256").digest(actual).joinToString("") { "%02x".format(it) })
                        context.contentResolver.query(Uri.parse(outputUri), arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.OWNER_PACKAGE_NAME), null, null, null)!!.use {
                            assertTrue(it.moveToFirst())
                            assertEquals(0, it.getInt(0))
                            assertEquals(context.packageName, it.getString(1))
                        }
                    }
                } finally {
                    // Delete only the UUID row created by this fixture; never a name-based match.
                    val ownUri = createdUri ?: runCatching { db.tasks().publication(id)?.uri }.getOrNull()
                    if (ownUri != null) runCatching { context.contentResolver.delete(Uri.parse(ownUri), null, null) }
                    db.close()
                    stage.parentFile?.deleteRecursively()
                }
            }
        }
    }

    @Test fun regularDescriptorsRemainUsableOnSupportedAndroidVersions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "bmd-descriptor-${UUID.randomUUID()}.bin")
        val expected = ByteArray(8193) { (it % 251).toByte() }
        try {
            val writeDescriptor = ParcelFileDescriptor.open(file,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE)
            val writeFd = writeDescriptor.fileDescriptor
            try {
                assertTrue("A regular local file must be admitted, including on API26", admitProviderDescriptor(writeDescriptor))
                val writer = DescriptorWriter(writeDescriptor, regular = true)
                try {
                    writer.write(expected, 0, expected.size) { false }
                    writer.flushDurably()
                    writer.closeSuccessfully()
                } finally { writer.abort() }
                assertFalse("Successful publication close must release the write descriptor", writeFd.valid())
            } finally { writeDescriptor.close() }

            val readDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val readFd = readDescriptor.fileDescriptor
            try {
                assertTrue(admitProviderDescriptor(readDescriptor))
                val actual = ArrayList<Byte>(expected.size)
                DescriptorReader(readDescriptor).use { reader ->
                    val buffer = ByteArray(1024)
                    while (true) {
                        val count = reader.read(buffer) { false }
                        if (count < 0) break
                        for (index in 0 until count) actual.add(buffer[index])
                    }
                }
                assertArrayEquals(expected, actual.toByteArray())
                assertFalse("Read proof completion must release its descriptor", readFd.valid())
            } finally { readDescriptor.close() }
        } finally { file.delete() }
    }

    @Test @SdkSuppress(maxSdkVersion = 29)
    fun olderAndroidRejectsReadAndWritePipeDescriptorsAndClosesThem() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            // An open opposite end means a blocking read could wait forever. Admission must reject
            // both directions using descriptor metadata only, before any read/write is attempted.
            for ((index, descriptor) in pipe.withIndex()) {
                val fd = descriptor.fileDescriptor
                assertTrue(fd.valid())
                val failure = runCatching { admitProviderDescriptor(descriptor) }.exceptionOrNull()
                assertTrue("Pipe end $index must produce actionable unsupported-stream recovery, got $failure",
                    failure is UnsupportedProviderStream)
                assertTrue(failure?.message.orEmpty().contains("local device folder"))
                assertFalse("Rejected pipe end $index leaked its descriptor", fd.valid())
            }
        } finally { pipe.forEach { runCatching { it.close() } } }
    }
}
