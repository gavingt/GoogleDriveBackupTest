package com.gavinsappcreations.googledrivebackuptest

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileOutputStream


/**
 * We only have a Uri pointing to the backup directory, but we need a java.io.File
 * in order to upload a document to Google Drive. So we use streams to create a
 * temporary java.io.File in externalCacheDir for each document.
 */
fun createTempFileFromDocumentFile(activity: Activity, documentFile: DocumentFile): java.io.File {
    val tempFile = java.io.File(activity.externalCacheDir, documentFile.name ?: "tempFileName")
    tempFile.createNewFile()
    val inputStream = activity.contentResolver.openInputStream(documentFile.uri)!!
    val outputStream = FileOutputStream(tempFile)
    inputStream.copyTo(outputStream)
    inputStream.close()
    outputStream.close()
    return tempFile
}


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