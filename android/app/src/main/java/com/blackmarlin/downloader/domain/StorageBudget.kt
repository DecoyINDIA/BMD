package com.blackmarlin.downloader.domain

/** Estimates, never filesystem reservations. Saturation prevents hostile Content-Length overflow. */
object StorageBudget {
    const val SAFETY_BYTES = 64L * 1024 * 1024
    fun sum(a: Long, b: Long): Long = if (a.coerceAtLeast(0) > Long.MAX_VALUE - b.coerceAtLeast(0)) Long.MAX_VALUE else a.coerceAtLeast(0) + b.coerceAtLeast(0)
    fun additionalBytes(totalBytes: Long?, allocatedPrivateBytes: Long, copyOnPrivateVolume: Boolean): Long {
        val total = totalBytes?.coerceAtLeast(0) ?: return 0
        val privateRemaining = (total - allocatedPrivateBytes.coerceAtLeast(0).coerceAtMost(total)).coerceAtLeast(0)
        return sum(privateRemaining, if (copyOnPrivateVolume) total else 0)
    }
    fun requiredFreeBytes(totalBytes: Long?, allocatedPrivateBytes: Long = 0, copyOnPrivateVolume: Boolean = true): Long =
        sum(additionalBytes(totalBytes, allocatedPrivateBytes, copyOnPrivateVolume), SAFETY_BYTES)
}
