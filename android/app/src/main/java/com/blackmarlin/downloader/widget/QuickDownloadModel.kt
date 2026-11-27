package com.blackmarlin.downloader.widget

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.blackmarlin.downloader.R
import com.blackmarlin.downloader.bmdGraph
import com.blackmarlin.downloader.ui.BmdCommand
import com.blackmarlin.downloader.ui.BmdUiException
import com.blackmarlin.downloader.ui.InspectLinkRequest
import com.blackmarlin.downloader.ui.StartDownloadRequest
import com.blackmarlin.downloader.ui.parseSourceOrigin
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class QuickDownloadConsent(val httpOrigin: String? = null, val httpAllowed: Boolean = false)

data class QuickDownloadForm(
    val url: String = "",
    val error: String? = null,
    val busy: Boolean = false,
    val edited: Boolean = false,
    val consent: QuickDownloadConsent? = null,
)

class QuickDownloadModel(private val app: Application) : AndroidViewModel(app) {
    private val graph = app.bmdGraph()
    private val widgets = graph.quickDownloads
    private val mutableForm = MutableStateFlow(QuickDownloadForm())
    val form = mutableForm.asStateFlow()
    private var widgetId = 0

    fun attach(id: Int) { widgetId = id }

    fun edit(url: String) {
        if (!form.value.busy) mutableForm.value = QuickDownloadForm(url = url, edited = true)
    }

    fun clearUrl() { mutableForm.value = QuickDownloadForm() }

    fun decline() { mutableForm.value = form.value.copy(consent = null) }

    fun allow() {
        val consent = form.value.consent ?: return
        if (consent.httpOrigin != null) submit(allowHttp = true)
        else submit(allowHttp = consent.httpAllowed, allowMetered = true)
    }

    fun submit() = submit(allowHttp = false)

    private fun submit(allowHttp: Boolean, allowMetered: Boolean = false) {
        val current = form.value
        if (current.busy || current.url.isBlank()) return
        val url = current.url.trim()
        val origin = parseSourceOrigin(url)
        if (origin == null) {
            mutableForm.value = current.copy(error = app.getString(R.string.quick_download_direct_link))
            return
        }
        if (origin.startsWith("http://") && !allowHttp) {
            mutableForm.value = current.copy(consent = QuickDownloadConsent(httpOrigin = origin))
            return
        }
        mutableForm.value = current.copy(busy = true, error = null, consent = null)
        viewModelScope.launch {
            try {
                val settings = graph.preferences.state.value
                val review = graph.repository.inspectLink(InspectLinkRequest(
                    url, settings.defaultNetwork, allowMeteredOnce = allowMetered, allowPlainHttp = allowHttp,
                ))
                when {
                    review.requiresCredentials -> throw BmdUiException(app.getString(R.string.quick_download_sign_in))
                    review.possibleHtmlPage -> throw BmdUiException(app.getString(R.string.quick_download_direct_link))
                    review.existingTaskId != null -> throw BmdUiException(app.getString(R.string.quick_download_duplicate))
                }
                val request = StartDownloadRequest(
                    UUID.randomUUID().toString(), review, review.suggestedName, settings.defaultDestination, settings.defaultNetwork,
                    allowMeteredOnce = allowMetered, allowPlainHttp = allowHttp,
                )
                // Once persisted, keep the home-screen tile connected even if the panel closes.
                withContext(NonCancellable) {
                    val taskId = checkNotNull(graph.repository.execute(BmdCommand.StartDownload(request)).taskId)
                    widgets.track(widgetId, taskId)
                    mutableForm.value = form.value.copy(edited = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: BmdUiException) {
                mutableForm.value = if (error.recovery == BmdUiException.Recovery.METERED_CONSENT) {
                    form.value.copy(consent = QuickDownloadConsent(httpAllowed = allowHttp))
                } else form.value.copy(error = error.userMessage)
            } catch (_: Exception) {
                mutableForm.value = form.value.copy(error = app.getString(R.string.quick_download_error))
            } finally {
                mutableForm.value = form.value.copy(busy = false)
            }
        }
    }
}
