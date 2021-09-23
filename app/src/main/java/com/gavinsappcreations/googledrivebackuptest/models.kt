package com.gavinsappcreations.googledrivebackuptest

import android.net.Uri

// Used for encapsulating loading state when doing asynchronous calls.
sealed class State<T> {
    class Loading<T> : State<T>()
    data class Success<T>(val data: T) : State<T>()
    data class Failed<T>(val throwable: Throwable) : State<T>()
}


/**
 * Stores subdirectory info that we read from Google Drive.
 * We use this to build up the directory hierarchy during the backup procedure.
 * This class is suitable for use with a Map that's keyed to the subdirectory's parentDirectoryId.
 */
data class SubdirectoryContainer(
    val subdirectoryId: String,
    val subdirectoryName: String,
    var subdirectoryUri: Uri?
)


/**
 * Same as SubdirectoryContainer, except also include parentDirectoryId.
 * This class is suitable for use with a Set.
 */
data class SubdirectoryContainerWithParent(
    val directoryId: String,
    val directoryName: String,
    var directoryUri: Uri?,
    val parentDirectoryId: String
)


/**
 * Indicates the current step in the backup process that's being performed.
 */
enum class BackupStatus {
    INACTIVE,
    DELETING_OLD_FILES,
    FETCHING_ROOT_DIRECTORY_ID,
    DOWNLOADING_DIRECTORY_INFO,
    CREATING_DIRECTORIES,
    DOWNLOADING_FILES
}


/**
 * Indicates the current step in the restore process that's being performed.
 */
enum class RestoreStatus {
    INACTIVE,
/*    DELETING_OLD_FILES,
    FETCHING_ROOT_DIRECTORY_ID,
    DOWNLOADING_DIRECTORIES,
    CREATING_DIRECTORIES,*/
    UPLOADING_FILES,
    UPLOAD_PAUSED
}
