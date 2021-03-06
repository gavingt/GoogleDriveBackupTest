package com.gavinsappcreations.googledrivebackuptest

import android.net.Uri
import android.view.SearchEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DriveViewModel : ViewModel() {

    // Holds current state of the Fragment.
    data class DriveState(
        var googleSignInAccount: GoogleSignInAccount? = null,
        var googleDriveService: Drive? = null,
        var rootDirectoryUri: Uri? = null,
        var backupStatus: BackupStatus = BackupStatus.INACTIVE,
        var restoreStatus: RestoreStatus = RestoreStatus.INACTIVE
    )

    // Channel for sending one-off events from viewModel to Fragment.
    private val _eventChannel = Channel<SearchEvent>(Channel.UNLIMITED)

    // Turn eventChannel into a Flow that the Fragment can use to receive events.
    val eventFlow = _eventChannel.receiveAsFlow()

    // Private mutable Flow representing the state of the Fragment.
    private val _viewState = MutableStateFlow(DriveState())

    // Public non-mutable Flow representing the state of the Fragment.
    val viewState = _viewState.asStateFlow()

    fun updateUserGoogleSignInAccount(account: GoogleSignInAccount?) {
        _viewState.update { it.copy(googleSignInAccount = account) }
    }

    fun updateGoogleDriveService(driveService: Drive?) {
        _viewState.update { it.copy(googleDriveService = driveService) }
    }

    fun updateRootDirectoryUri(uri: Uri) {
        _viewState.update { it.copy(rootDirectoryUri = uri) }
    }

    fun updateBackupStatus(newStatus: BackupStatus) {
        _viewState.update { it.copy(backupStatus = newStatus) }
    }

    fun updateRestoreStatus(newStatus: RestoreStatus) {
        _viewState.update { it.copy(restoreStatus = newStatus) }
    }

}