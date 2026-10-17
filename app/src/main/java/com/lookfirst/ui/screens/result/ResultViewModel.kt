package com.lookfirst.ui.screens.result

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lookfirst.data.platform.ClipboardWriter
import com.lookfirst.di.DefaultDispatcher
import com.lookfirst.domain.decoder.PayloadParser
import com.lookfirst.domain.model.ParseResult
import com.lookfirst.domain.model.Untrusted
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ResultEvent {
    /** The text is on the clipboard: the screen shows the "Copied" snackbar. */
    data object Copied : ResultEvent
}

/**
 * Holds the scanned text for as long as the Result screen is open. The text is never logged and never put in a
 * navigation argument. Once a text is shown, [show] ignores every other one: a second code in front of the
 * camera can never replace the content under the user's finger (threat model T-UI-4).
 */
@HiltViewModel
class ResultViewModel @Inject constructor(
    private val clipboard: ClipboardWriter,
    @DefaultDispatcher private val parseDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ResultUiState>(ResultUiState.NoContent)
    val state: StateFlow<ResultUiState> = mutableState.asStateFlow()

    private val eventChannel = Channel<ResultEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    private var scanned: Untrusted? = null
    private var copyIsSecret = false
    private var parsing: Job? = null

    /**
     * Starts showing [content]. Ignored while another content is displayed. [multipleCodes]: the image it comes
     * from held other codes, a line says that only this one is shown (D14).
     */
    fun show(content: Untrusted, multipleCodes: Boolean = false) {
        if (scanned != null) return
        scanned = content
        mutableState.value = ResultUiState.Parsing
        parsing = viewModelScope.launch {
            val parsed = withContext(parseDispatcher) { PayloadParser.parse(content.value) }
            copyIsSecret = (parsed as? ParseResult.Ok)?.payload?.containsSecret() == true
            mutableState.value = parsed.toUiState(multipleCodes)
        }
    }

    /** Copy: straight away, or after the confirmation dialog when the text contains a secret (ux.md 4.6). */
    fun onCopy() {
        if (mutableState.value !is ResultUiState.Content) return
        if (copyIsSecret) {
            mutableState.update { if (it is ResultUiState.Content) it.copy(confirmingSecretCopy = true) else it }
        } else {
            copyToClipboard()
        }
    }

    fun onConfirmSecretCopy() {
        val current = mutableState.value
        if (current !is ResultUiState.Content || !current.confirmingSecretCopy) return
        mutableState.value = current.copy(confirmingSecretCopy = false)
        copyToClipboard()
    }

    fun onDismissSecretCopy() {
        mutableState.update { if (it is ResultUiState.Content) it.copy(confirmingSecretCopy = false) else it }
    }

    /** Cancel / back: forget the content. */
    fun clear() {
        parsing?.cancel()
        parsing = null
        scanned = null
        copyIsSecret = false
        mutableState.value = ResultUiState.NoContent
    }

    private fun copyToClipboard() {
        val text = scanned?.value ?: return
        clipboard.copy(text, sensitive = copyIsSecret)
        eventChannel.trySend(ResultEvent.Copied)
    }
}
