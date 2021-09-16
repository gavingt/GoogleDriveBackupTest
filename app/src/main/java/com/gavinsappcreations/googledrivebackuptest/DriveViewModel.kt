package com.gavinsappcreations.googledrivebackuptest

import android.net.Uri
import android.view.SearchEvent
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

class DriveViewModel : ViewModel() {

    // Holds current state of the Fragment.
    data class DriveState(
        var isUserSignedIn: Boolean = false,
        var rootDirectoryUri: Uri? = null,
        var isBackupInProgress: Boolean = false,
        var userEmailAddress: String? = null
    )

    // Channel for sending one-off events from viewModel to Fragment.
    private val _eventChannel = Channel<SearchEvent>(Channel.UNLIMITED)

    // Turn eventChannel into a Flow that the Fragment can use to receive events.
    val eventFlow = _eventChannel.receiveAsFlow()

    // Private mutable Flow representing the state of the Fragment.
    private val _viewState = MutableStateFlow(DriveState())

    // Public non-mutable Flow representing the state of the Fragment.
    val viewState = _viewState.asStateFlow()

    fun updateUserGoogleSignInStatus(isUserSignedIn: Boolean) {
        _viewState.update { it.copy(isUserSignedIn = isUserSignedIn) }
    }

    fun updateRootDirectoryUri(uri: Uri) {
        _viewState.update { it.copy(rootDirectoryUri = uri) }
    }

    fun updateIsBackupInProgress(isInProgress: Boolean) {
        _viewState.update { it.copy(isBackupInProgress = isInProgress) }
    }

    fun updateUserEmailAddress(emailAddress: String?) {
        _viewState.update { it.copy(userEmailAddress = emailAddress) }
    }
}