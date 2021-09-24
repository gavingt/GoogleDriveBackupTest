package com.gavinsappcreations.googledrivebackuptest

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream

fun getFileSizeInBytes(file: java.io.File): Int {
    return java.lang.String.valueOf(file.length()).toInt()
}


fun getFilesToUploadSize(filesToBeUploaded: List<java.io.File>): Long {
    var totalLength: Long = 0

    for (fileToUpload in filesToBeUploaded) {
        totalLength += fileToUpload.length()
    }

    return totalLength
}


/**
 * Returns boolean indicating whether the provided tree Uri points to a directory that actually exists.
 */
fun fileForDocumentTreeUriExists(activity: Activity, documentTreeUri: Uri) =
    DocumentFile.fromTreeUri(activity, documentTreeUri)?.exists() ?: false



suspend fun getOrCreateDirectory(activity: Activity, parentUri: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
    DocumentsContract.createDocument(
        activity.contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
    )
}


// Constructs a query of the form "'folderA-ID' in parents or 'folderA1-ID' in parents or 'folderA1a-ID' in parents"
// Use something like this for selecting specific folders/files to back up.
/*    private fun fetchCurrentQuery(): String {

            var directorySelection = ROOT_DIRECTORY
            for (directory in directoryIdsAndParentIds) {
                directorySelection = directorySelection.plus("'${directory.key}' in parents")
                directorySelection = directorySelection.plus(" or ")
            }
            return directorySelection.plus(" and trashed = false")
    }*/