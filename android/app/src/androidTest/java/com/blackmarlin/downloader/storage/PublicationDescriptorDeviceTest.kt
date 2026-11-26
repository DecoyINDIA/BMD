package com.blackmarlin.downloader.storage

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual Android descriptors at the same admission boundary used by provider I/O. */
@RunWith(AndroidJUnit4::class)
class PublicationDescriptorDeviceTest {
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
