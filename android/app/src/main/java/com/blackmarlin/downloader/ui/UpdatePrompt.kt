package com.blackmarlin.downloader.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackmarlin.downloader.AndroidUpdate
import com.blackmarlin.downloader.AndroidUpdates
import com.blackmarlin.downloader.R

@Composable
internal fun UpdatePrompt(updates: AndroidUpdates, install: () -> Unit) {
    val state by updates.update.collectAsStateWithLifecycle()
    when (val update = state) {
        is AndroidUpdate.Available -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text(stringResource(R.string.update_available, update.release.version)) },
            text = { Text(stringResource(if (update.retry) R.string.update_failed else R.string.update_message)) },
            confirmButton = {
                TextButton(onClick = updates::download) {
                    Text(stringResource(if (update.retry) R.string.update_retry else R.string.update_now))
                }
            },
            dismissButton = { TextButton(onClick = updates::dismiss) { Text(stringResource(R.string.update_later)) } },
        )
        is AndroidUpdate.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_downloading, update.version)) },
            text = { LinearProgressIndicator() },
            confirmButton = {},
        )
        is AndroidUpdate.Ready -> AlertDialog(
            onDismissRequest = updates::dismiss,
            title = { Text(stringResource(R.string.update_ready, update.version)) },
            text = { Text(stringResource(R.string.update_install_message)) },
            confirmButton = { TextButton(onClick = install) { Text(stringResource(R.string.update_install)) } },
            dismissButton = { TextButton(onClick = updates::dismiss) { Text(stringResource(R.string.update_later)) } },
        )
        null -> Unit
    }
}
