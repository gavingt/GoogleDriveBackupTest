package com.gavinsappcreations.googledrivebackuptest

import android.net.Uri

// Used for encapsulating loading state when doing asynchronous calls.
sealed class State<T> {
    class Loading<T> : State<T>()
    data class Success<T>(val data: T) : State<T>()
    data class Failed<T>(val throwable: Throwable) : State<T>()
}


/**
 * Stores directory info that we read from Google Drive.
 * We use this to build up the directory hierarchy during the backup procedure.
 */
data class DirectoryInfoContainerFull(val directoryId: String, val directoryName: String, var directoryUri: Uri?, val parentId: String)

data class DirectoryInfoContainer(val directoryId: String, val directoryName: String, var directoryUri: Uri?)



enum class OperationType {
    DELETING_OLD_FILES,
    FETCHING_ROOT_DIRECTORY_ID,
    DOWNLOADING_DIRECTORIES,
    CREATING_DIRECTORIES,
    DOWNLOADING_FILES,
    BACKUP_COMPLETE
}

