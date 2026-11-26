package com.blackmarlin.downloader.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** The caller must hold the task gate and prove that no writer or unresolved save owns these bytes. */
internal fun deletePrivateTransferDirectory(noBackupDirectory: File, taskId: String) {
    require(taskId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
    val parent = noBackupDirectory.toPath().resolve("transfers")
    val root = parent.resolve(taskId)
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return

    // Android may expose its trusted app directory through /data/user/0 -> /data/data.
    // Resolve that trusted prefix, but never accept a redirected transfers or task root.
    val expectedParent = noBackupDirectory.toPath().toRealPath().resolve("transfers")
    require(!Files.isSymbolicLink(parent))
    require(parent.toRealPath() == expectedParent)
    require(!Files.isSymbolicLink(root))

    // walk does not follow symbolic links; an interior link is unlinked, not traversed.
    Files.walk(root).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
