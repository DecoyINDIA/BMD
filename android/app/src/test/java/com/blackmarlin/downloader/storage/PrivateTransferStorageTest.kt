package com.blackmarlin.downloader.storage

import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrivateTransferStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun trustedAndroidStyleAncestorAliasAllowsOnlyTheCompletedTaskToBeRemoved() {
        val data = temporary.newFolder("data").toPath()
        val noBackup = Files.createDirectories(data.resolve("app/no_backup"))
        val parent = Files.createDirectories(noBackup.resolve("transfers"))
        val task = Files.createDirectory(parent.resolve("completed-task"))
        Files.write(task.resolve("verified.payload"), byteArrayOf(1, 2, 3))
        val neighbor = Files.createDirectory(parent.resolve("retained-task"))
        val retained = byteArrayOf(7, 8, 9)
        Files.write(neighbor.resolve("payload.bmd.part"), retained)
        val userZero = temporary.root.toPath().resolve("user-zero")
        Files.createSymbolicLink(userZero, data)

        val aliasedNoBackup = userZero.resolve("app/no_backup").toFile()
        deletePrivateTransferDirectory(aliasedNoBackup, "completed-task")

        assertFalse(Files.exists(task, LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals(retained, Files.readAllBytes(neighbor.resolve("payload.bmd.part")))
        assertTrue(Files.isSymbolicLink(userZero))
        deletePrivateTransferDirectory(aliasedNoBackup, "completed-task") // Idempotent after success.
    }

    @Test fun redirectedTransfersRootIsRejectedAndItsTargetIsPreserved() {
        val noBackup = temporary.newFolder("no_backup").toPath()
        val outside = temporary.newFolder("outside").toPath()
        val task = Files.createDirectory(outside.resolve("task"))
        val sentinel = byteArrayOf(11, 12, 13)
        Files.write(task.resolve("sentinel"), sentinel)
        Files.createSymbolicLink(noBackup.resolve("transfers"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            deletePrivateTransferDirectory(noBackup.toFile(), "task")
        }
        assertArrayEquals(sentinel, Files.readAllBytes(task.resolve("sentinel")))
        assertTrue(Files.isSymbolicLink(noBackup.resolve("transfers")))
    }

    @Test fun redirectedTaskRootIsRejectedAndItsTargetIsPreserved() {
        val noBackup = temporary.newFolder("no_backup").toPath()
        val parent = Files.createDirectory(noBackup.resolve("transfers"))
        val outside = temporary.newFolder("outside").toPath()
        val sentinel = byteArrayOf(21, 22, 23)
        Files.write(outside.resolve("sentinel"), sentinel)
        Files.createSymbolicLink(parent.resolve("task"), outside)

        assertThrows(IllegalArgumentException::class.java) {
            deletePrivateTransferDirectory(noBackup.toFile(), "task")
        }
        assertArrayEquals(sentinel, Files.readAllBytes(outside.resolve("sentinel")))
        assertTrue(Files.isSymbolicLink(parent.resolve("task")))
    }

    @Test fun linksInsideTheCompletedTaskAreRemovedWithoutFollowingThem() {
        val noBackup = temporary.newFolder("no_backup").toPath()
        val task = Files.createDirectories(noBackup.resolve("transfers/task"))
        val outside = temporary.newFolder("outside").toPath()
        val sentinel = byteArrayOf(31, 32, 33)
        Files.write(outside.resolve("sentinel"), sentinel)
        Files.createSymbolicLink(task.resolve("outside-link"), outside)
        Files.write(task.resolve("verified.payload"), byteArrayOf(1, 2, 3))

        deletePrivateTransferDirectory(noBackup.toFile(), "task")

        assertFalse(Files.exists(task, LinkOption.NOFOLLOW_LINKS))
        assertArrayEquals(sentinel, Files.readAllBytes(outside.resolve("sentinel")))
    }
}
