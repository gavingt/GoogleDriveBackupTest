package com.gavinsappcreations.googledrivebackuptest

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
data class DirectoryInfoContainer(val directoryId: String, val parentId: String, val directoryName: String)
