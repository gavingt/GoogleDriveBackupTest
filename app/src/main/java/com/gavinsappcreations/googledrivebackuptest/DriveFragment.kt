package com.gavinsappcreations.googledrivebackuptest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.asLiveData
import androidx.lifecycle.lifecycleScope
import com.gavinsappcreations.googledrivebackuptest.databinding.FragmentDriveBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.android.material.snackbar.Snackbar
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.FileContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.*


/**
 * In the app we’re building, we will need to show a ‘Searching…’ screen which counts the number of files
 * found in user’s Google Drive, when we count files, we will need to categorize files into
 * photo/video/audio/document/others. When search is complete, the app will switch to ‘Backing up…’ screen
 * with a progress like ‘10 of 104 has been copied’ and will download files one by one.
 *
 * We will need to count the number of files before downloading any of them.
 *
 * We can’t create all subfolders at a time, we will have to create folders as needed. For example
 * if there are 400 folders created, but the app crashed while download the 1st file, then we left
 * 400 empty folders on the drive which is not good. Also, it takes time to create 400 folders,
 * and will not be a good user experience.
 *
 * Optionally, is it possible to enumerate through Google Drive only once?
 * I see we are enumerating all folders, and then later enumerating all non-folder files.
 * If we can enumerate all objects during ‘Searching…’, count file objects and also build folder structure, then would be great.
 */

// TODO: handle Google Workspace files
// TODO: implement refined restore procedure

// TODO: Test parallel uploads/downloads. Parallel uploading for small files in particular is potentially much faster.
// TODO: error handling (both local and on network): https://developers.google.com/drive/api/v3/handle-errors#resolve_a_403_error_rate_limit_exceeded
// TODO: only allow backup/restore on wifi
// TODO: add try-catch to each method, and in catch block throw exceptions with custom messages
// TODO: store ids in sqlite for each file/folder backup up (and possibly for restored items as well)

// TODO: Create the directory on demand as I'm creating the file.
// TODO: Download metadata like total # of folders, total # files, total size of Drive,
//       and total number of each filetype (photo/video/audio/document/others) before starting backup process.
//       Do this all at once instead of splitting it up into folders and then files.
// TODO: Add cancel option (by pressing backupButton while backup is in progress).
// TODO: Use WorkManager in ForegroundService mode to disconnect the backup/restore processes from the UI?:
//       https://www.raywenderlich.com/20689637-scheduling-tasks-with-android-workmanager


@Suppress("BlockingMethodInNonBlockingContext")
class DriveFragment : Fragment() {

    private val viewModel by viewModels<DriveViewModel>()
    private lateinit var binding: FragmentDriveBinding

    // Map of directories whose key = parent directory id and value = list of child directories inside the parent directory.
    // If a directory doesn't have any child directories, it won't be listed as a key.
    private val directoryMap: MutableMap<String, List<SubdirectoryContainer>> = mutableMapOf()

    var filesProcessed = 0
    var rootDirectoryDocumentFile: DocumentFile? = null
    private lateinit var credential: GoogleAccountCredential

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_drive, container, false)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel

        // Here we specify what to do with the Google sign-in result.
        val googleSignInResultLauncher: ActivityResultLauncher<Intent> =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val data: Intent = result.data!!
                    handleSignInPromptResult(data)
                } else {
                    Toast.makeText(
                        requireActivity(), "Sign-in failed with resultCode: ${result.resultCode}. " +
                                "This is probably an OAuth or debug keystore issue.", Toast.LENGTH_LONG
                    ).show()
                }
            }

        // Here we specify what to do with the Uri permission result.
        val permissionsLauncher: ActivityResultLauncher<Intent> =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val uri = result.data!!.data
                    updateRootDirectoryUri(uri!!)
                    requireActivity().getPreferences(Context.MODE_PRIVATE).edit()
                        .putString(KEY_ROOT_DIRECTORY_URI, uri.toString()).apply()
                    requireActivity().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    viewModel.updateRootDirectoryUri(rootDirectoryDocumentFile!!.uri)
                } else {
                    Toast.makeText(requireActivity(), "permissions request cancelled", Toast.LENGTH_SHORT).show()
                }
            }

        updateRootDirectoryUri(verifyAndFetchRootDirectoryUri())
        val googleAccount = GoogleSignIn.getLastSignedInAccount(requireActivity())
        viewModel.updateUserGoogleSignInAccount(googleAccount)
        getDriveService(googleAccount)

        binding.logInButton.setOnClickListener {
            if (viewModel.viewState.value.googleSignInAccount == null) {
                showSignInPrompt(googleSignInResultLauncher)
            } else {
                signOut()
            }
        }

        binding.grantUsbPermissionsButton.setOnClickListener {
            promptForPermissionsInSAF(permissionsLauncher)
        }

        binding.backupButton.setOnClickListener {
            startBackupProcedure()
        }

        binding.restoreButton.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                // TODO: like we did for createDirectoryStructure(), make recursive method an inner method so we can remove other operations from here.
                viewModel.updateRestoreStatus(RestoreStatus.UPLOADING_FILES)
                setRestoreButtonText(RestoreStatus.UPLOADING_FILES)
                startRestoreProcedure(rootDirectoryDocumentFile!!, "root")
                requireActivity().externalCacheDir?.deleteRecursively()
                withContext(Dispatchers.Main) {
                    Snackbar.make(binding.root, "Restore complete!", Snackbar.LENGTH_LONG).show()
                }
                viewModel.updateRestoreStatus(RestoreStatus.INACTIVE)
                setRestoreButtonText(RestoreStatus.INACTIVE)
            }
        }

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Here we observe and react to changes in our view's state, which is stored in our ViewModel.
        viewModel.viewState.asLiveData().observe(viewLifecycleOwner) { state ->

            val arePrerequisitesMet =
                state.googleSignInAccount != null && state.googleDriveService != null && state.rootDirectoryUri != null

            val isBackupOrRestoreInProgress =
                state.backupStatus != BackupStatus.INACTIVE || state.restoreStatus != RestoreStatus.INACTIVE

            binding.backupButton.isEnabled = arePrerequisitesMet && !isBackupOrRestoreInProgress
            binding.restoreButton.isEnabled = arePrerequisitesMet && !isBackupOrRestoreInProgress
            binding.progressBar.visibility = when (isBackupOrRestoreInProgress) {
                true -> View.VISIBLE
                false -> View.GONE
            }

            binding.logInButton.text = when (state.googleSignInAccount == null) {
                true -> "Log into Google account"
                false -> "Logged in as ${state.googleSignInAccount!!.email}.\n\nLog out?"
            }

            binding.grantUsbPermissionsButton.text = when (state.rootDirectoryUri) {
                null -> "Grant USB drive permissions"
                else -> {
                    when (rootDirectoryDocumentFile) {
                        null -> "Grant backup directory permissions"
                        else -> "Current backup directory: \n/${rootDirectoryDocumentFile!!.name}"
                    }
                }
            }
        }
    }


    private fun startBackupProcedure() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                deleteAllFilesInBackupDirectory()
                val googleDriveRootDirectoryId = fetchGoogleDriveRootDirectoryId()
                downloadDirectoryInfo()
                val backupDirectoryUri = getOrCreateDirectory(requireActivity(), rootDirectoryDocumentFile!!.uri, BACKUP_DIRECTORY)!!
                createDirectoryStructure(googleDriveRootDirectoryId, backupDirectoryUri)
                copyFilesFromGoogleDriveToLocalDirectory(directoryMap)
            } catch (throwable: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireActivity(), "Error: ${throwable.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }


    /**
     * For testing purposes, delete all current files in backup directory before starting. This
     * only deletes files in the "backup_directory" folder we create, so it won't delete your personal files
     */
    private suspend fun deleteAllFilesInBackupDirectory() {
        viewModel.updateBackupStatus(BackupStatus.DELETING_OLD_FILES)
        setBackupButtonText(BackupStatus.DELETING_OLD_FILES)

        val listOfBackupDirectoryFiles: Array<DocumentFile> = rootDirectoryDocumentFile!!.listFiles()
        for (file in listOfBackupDirectoryFiles) {
            file.delete()
        }

        withContext(Dispatchers.Main) {
            ("Old backup files deleted").print()
        }
    }

    // TODO: rather than a network request, in this method we could search directoryMap for the one
    //       directory that doesn't have a parent directory (that's the root)

    private suspend fun fetchGoogleDriveRootDirectoryId(): String {
        viewModel.updateBackupStatus(BackupStatus.FETCHING_ROOT_DIRECTORY_ID)
        setBackupButtonText(BackupStatus.FETCHING_ROOT_DIRECTORY_ID)

        val googleDriveService = viewModel.viewState.value.googleDriveService!!
        val result = googleDriveService.files().get("root").apply {
            fields = "id"
        }.execute()

        withContext(Dispatchers.Main) {
            ("Found root directoryId: ${result.id}").print()
        }

        return result.id
    }


    private suspend fun downloadDirectoryInfo() {
        val tempDirectoryMap: MutableMap<String, MutableList<SubdirectoryContainer>> = mutableMapOf()
        filesProcessed = 0

        viewModel.updateBackupStatus(BackupStatus.DOWNLOADING_DIRECTORY_INFO)

        var pageToken: String? = null
        do {
            val googleDriveService = viewModel.viewState.value.googleDriveService!!
            val result = googleDriveService.files().list().apply {
                spaces = "drive"
                pageSize = 1000 // This gets ignored and set to a value of 460 by the API.
                // Select files that are folders, aren't in trash, and which user owns.
                q = "mimeType = 'application/vnd.google-apps.folder' and trashed = false and 'me' in owners"
                fields = "nextPageToken, files(id, name, parents)"
                this.pageToken = pageToken
            }.execute()

            for (file in result.files) {
                (file.name).print()
                (file.parents ?: "null").print()

                val parentUri = file.parents[0]

                if (tempDirectoryMap[parentUri] == null) {
                    tempDirectoryMap[parentUri] = mutableListOf(SubdirectoryContainer(file.id, file.name, null))
                } else {
                    tempDirectoryMap[parentUri]!!.add(SubdirectoryContainer(file.id, file.name, null))
                }

                filesProcessed++
                setBackupButtonText(BackupStatus.DOWNLOADING_DIRECTORY_INFO)
            }
            pageToken = result.nextPageToken
        } while (pageToken != null)

        directoryMap.clear()
        directoryMap.putAll(tempDirectoryMap)
    }


    private suspend fun createDirectoryStructure(directoryBeingBuiltId: String, directoryBeingBuiltUri: Uri) {
        // This inner method is called recursively to create each branch of subdirectories.
        suspend fun createBranch(directoryBeingBuiltId: String, directoryBeingBuiltUri: Uri) {
            coroutineScope {
                // Create subdirectories for all children of directoryBeingBuilt.
                directoryMap[directoryBeingBuiltId]?.forEach { childDirectory ->

                    // Create a directory for each childDirectory and set its Uri in directoryMap.
                    val currentSubdirectoryUri =
                        getOrCreateDirectory(requireActivity(), directoryBeingBuiltUri, childDirectory.subdirectoryName)!!
                    childDirectory.subdirectoryUri = currentSubdirectoryUri

                    filesProcessed++
                    setBackupButtonText(BackupStatus.CREATING_DIRECTORIES)

                    // Set the subdirectory we just built as directoryBeingBuilt and re-run this method.
                    launch {
                        createBranch(childDirectory.subdirectoryId, currentSubdirectoryUri)
                    }
                }
            }
        }

        filesProcessed = 0
        viewModel.updateBackupStatus(BackupStatus.CREATING_DIRECTORIES)
        createBranch(directoryBeingBuiltId, directoryBeingBuiltUri)
    }


    private suspend fun copyFilesFromGoogleDriveToLocalDirectory(subdirectoryMap: MutableMap<String, List<SubdirectoryContainer>>) {

        setBackupButtonText(BackupStatus.DOWNLOADING_FILES)

        filesProcessed = 0
        var pageToken: String? = null
        val googleDriveService = viewModel.viewState.value.googleDriveService!!

        // We transform subdirectoryMap into subdirectorySet, as it's faster to work with a Set in this method.
        val subdirectorySet = mutableSetOf<SubdirectoryContainerWithParent>()
        subdirectoryMap.forEach {
            for (entry in it.value) {
                subdirectorySet.add(SubdirectoryContainerWithParent(entry.subdirectoryId, entry.subdirectoryName, entry.subdirectoryUri, it.key))
            }
        }

        fun fetchParentUriFromParentId(parentId: String): Uri? {
            val matchingDirectoryInfoContainer = subdirectorySet.find {
                it.directoryId == parentId
            }

            matchingDirectoryInfoContainer?.let {
                return it.directoryUri!!
            }
            return null
        }

        suspend fun createLocalFileFromGoogleDriveFile(googleDriveFile: File) {
            // If file size is less than the chunk size, don't show completion percentage as it'll never update.
            val showCompletionPercentage = googleDriveFile.getSize() > DOWNLOAD_CHUNK_SIZE_IN_BYTES
            when (showCompletionPercentage) {
                true -> setBackupButtonText(BackupStatus.DOWNLOADING_FILES, 0)
                false -> setBackupButtonText(BackupStatus.DOWNLOADING_FILES)
            }

            val parentId = googleDriveFile.parents[0]
            val parentUri = fetchParentUriFromParentId(parentId) ?: rootDirectoryDocumentFile!!.uri
            val parentDocumentFile = DocumentFile.fromTreeUri(requireActivity(), parentUri)

            val localDocumentFile = parentDocumentFile!!.createFile(googleDriveFile.mimeType, googleDriveFile.name)
            localDocumentFile?.let {
                val outputStream = requireActivity().contentResolver.openOutputStream(localDocumentFile.uri)!!
                val request = googleDriveService.files().get(googleDriveFile.id)
                request.mediaHttpDownloader.setProgressListener {
                    lifecycleScope.launch {
                        if (showCompletionPercentage) {
                            setBackupButtonText(BackupStatus.DOWNLOADING_FILES, (it.progress * 100).toInt())
                        }
                    }
                }
                request.executeMediaAndDownloadTo(outputStream)
                outputStream.close()
            }

            filesProcessed++
        }

        do {
            val filesStoredOnGoogleDrive = googleDriveService.files().list().apply {
                spaces = "drive"
                corpora = "user"
                // Select files that are not folders, aren't in trash, and which user owns.
                q = "mimeType != 'application/vnd.google-apps.folder' and trashed = false and 'me' in owners"
                fields = "nextPageToken, files(id, name, mimeType, parents, size)"
                this.pageToken = pageToken
            }.execute()

            for (googleDriveFile in filesStoredOnGoogleDrive.files) {
                ("Downloading - name: ${googleDriveFile.name}, mimeType: ${googleDriveFile.mimeType}, size: ${googleDriveFile.getSize()}").print()

                // TODO: For now we're just skipping over Google Workspace files since they need to be handled differently.
                //       For these we'll need to use googleDriveService.files().export() instead of googleDriveService.files().get().
                if (googleDriveFile.mimeType != DRIVE_FOLDER_MIME_TYPE && googleDriveFile.mimeType.startsWith(GOOGLE_WORKSPACE_FILE_MIME_TYPE_PREFIX)) {
                    continue
                }

                createLocalFileFromGoogleDriveFile(googleDriveFile)
            }
            pageToken = filesStoredOnGoogleDrive.nextPageToken
        } while (pageToken != null)

        viewModel.updateBackupStatus(BackupStatus.INACTIVE)
        setBackupButtonText(BackupStatus.INACTIVE)
        withContext(Dispatchers.Main) {
            Snackbar.make(binding.root, "Backup complete!", Snackbar.LENGTH_LONG).show()
        }
    }


    private suspend fun startRestoreProcedure(directoryBeingUploadedDocumentFile: DocumentFile, directoryBeingUploadedId: String) {
        val driveService = viewModel.viewState.value.googleDriveService!!

        val listOfFiles = directoryBeingUploadedDocumentFile.listFiles()
        for (documentFileToUpload in listOfFiles) {
            when (documentFileToUpload.isDirectory) {
                true -> {
                    val fileMetadata = File().apply {
                        name = documentFileToUpload.name
                        mimeType = "application/vnd.google-apps.folder"
                        parents = Collections.singletonList(directoryBeingUploadedId)
                    }

                    val directoryToUpload = driveService.files().create(fileMetadata)
                        .setFields("id, name, parents")
                        .execute()

                    withContext(Dispatchers.Main) {
                        ("Uploaded directory with name: ${directoryToUpload.name}, ID: ${directoryToUpload.id}").print()
                    }
                    filesProcessed++
                    setRestoreButtonText(RestoreStatus.UPLOADING_FILES)
                    // Run startRestoreProcedure() recursively to create documents inside this branch next.
                    startRestoreProcedure(documentFileToUpload, directoryToUpload.id)
                }
                false -> when (documentFileToUpload.length()) {
                    in 0..FIVE_MEGABYTES_IN_BYTES -> uploadSmallFile(documentFileToUpload, directoryBeingUploadedId)
                    else -> uploadLargeFileMetadata(documentFileToUpload, directoryBeingUploadedId)
                }
            }
        }
    }


    /**
     * We use this method for files < 5MB, since it lets us send metadata and file content in one HTTP request.
     * This corresponds to https://developers.google.com/drive/api/v3/manage-uploads#multipart
     */
    private suspend fun uploadSmallFile(documentFileToUpload: DocumentFile, parentId: String) {
        val googleDriveService = viewModel.viewState.value.googleDriveService!!
        val tempJavaFile = createTempFileFromDocumentFile(requireActivity(), documentFileToUpload)

        val fileMetadata = File()
        fileMetadata.name = documentFileToUpload.name
        fileMetadata.parents = Collections.singletonList(parentId)
        val mediaContent = FileContent(documentFileToUpload.type, tempJavaFile)

        googleDriveService.files().create(fileMetadata, mediaContent)
            .setFields("id, parents")
            .execute()
        filesProcessed++
        setRestoreButtonText(RestoreStatus.UPLOADING_FILES)
    }


    //TODO: refined restore procedure:
    //     1) enumerate all local files and folders
    //     2) enumerate all files/folders on Google Drive: https://github.com/rafa-guillermo/Google-Apps-Script-Useful-Snippets
    //     3) create list of all files contained locally but not on Google Drive.
    //     4) measure size of list created in step 3 (use getFilesToUploadSize() method below)
    //     5) check free space and remaining daily usage on Google Drive and ensure that list generated in step 3 can fit within it: https://developers.google.com/drive/api/v3/reference/about/
    //     6) upload list from step 3

    /**
     * Upload file metadata and get a sessionUrl. SessionUrl is where we actually upload the content to.
     * The URL is valid for one week. This is the beginning of the resumable upload process described here:
     * https://developers.google.com/drive/api/v3/manage-uploads#resumable
     */
    private suspend fun uploadLargeFileMetadata(documentFileToUpload: DocumentFile, parentId: String) {
        setRestoreButtonText(RestoreStatus.UPLOADING_FILES, 0)

        val mimeType = documentFileToUpload.type ?: UNKNOWN_FILE_MIME_TYPE
        val mediaFile = createTempFileFromDocumentFile(requireActivity(), documentFileToUpload)
        val fileSizeInBytes = documentFileToUpload.length()

        val requestUrl = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")
        val requestBody = "{\"name\": \"${documentFileToUpload.name}\", \"parents\": [\"$parentId\"]}"

        val request: HttpURLConnection = requestUrl.openConnection() as HttpURLConnection
        request.requestMethod = "POST"
        request.doInput = true
        request.doOutput = true
        request.setRequestProperty("Authorization", "Bearer " + credential.token)
        request.setRequestProperty("X-Upload-Content-Type", mimeType)
        request.setRequestProperty("X-Upload-Content-Length", fileSizeInBytes.toString())
        request.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        request.setRequestProperty(
            "Content-Length",
            java.lang.String.format(Locale.ENGLISH, "%d", requestBody.toByteArray().size)
        )

        val outputStream: OutputStream = request.outputStream
        outputStream.write(requestBody.toByteArray())
        outputStream.close()

        request.connect()

        if (request.responseCode == HttpURLConnection.HTTP_OK) {
            val uploadSessionUrl = URL(request.getHeaderField("location"))
            uploadLargeFileContent(mediaFile, uploadSessionUrl, fileSizeInBytes, mimeType)
        } else {
            throw IOException("Unable to start upload: HTTP response code ${request.responseCode} - ${request.responseMessage}")
        }
    }


    /**
     * Upload the actual file content for fileToUpload, sending it to uploadSesionUrl. While this method is
     * capable of resuming interrupted uploads, we haven't implemented the actual resume functionality yet:
     * https://developers.google.com/drive/api/v3/manage-uploads#resume-upload
     */
    private suspend fun uploadLargeFileContent(
        fileToUpload: java.io.File, uploadSessionUrl: URL,
        fileSizeInBytes: Long, mimeType: String
    ) {

        var beginningOfChunk: Long = 0
        var chunkSize = (4 * MediaHttpUploader.MINIMUM_CHUNK_SIZE).toLong()
        var chunksUploaded = 0

        // Upload fileToUpload once chunk at a time.
        do {
            setRestoreButtonText(RestoreStatus.UPLOADING_FILES, (beginningOfChunk * 100 / fileSizeInBytes).toInt())

            val request = uploadSessionUrl.openConnection() as HttpURLConnection
            request.requestMethod = "PUT"
            request.doOutput = true
            request.connectTimeout = 30000
            request.setRequestProperty("Content-Type", mimeType)

            if (beginningOfChunk + chunkSize > fileSizeInBytes) {
                chunkSize = fileSizeInBytes - beginningOfChunk
            }

            request.setRequestProperty(
                "Content-Length",
                java.lang.String.format(Locale.ENGLISH, "%d", chunkSize)
            )
            request.setRequestProperty(
                "Content-Range",
                "bytes " + beginningOfChunk + "-" + (beginningOfChunk + chunkSize - 1) + "/" + fileSizeInBytes
            )

            val buffer = ByteArray(chunkSize.toInt())
            val fileInputStream = FileInputStream(fileToUpload)
            fileInputStream.channel.position(beginningOfChunk)
            fileInputStream.read(buffer)
            fileInputStream.close()

            val outputStream = request.outputStream
            outputStream.write(buffer)
            outputStream.close()
            request.connect()

            ("Code ${request.responseCode} - ${request.responseMessage}, " +
                    "Byte range uploaded successfully: ${request.getHeaderField("Range")}").print()

            /**
             * Parse upper range from Range header, which tells us the last byte received successfully
             * by Google Drive. If this is equal to the last byte we sent, proceed to the next chunk.
             * If the Range header is null, no bytes have been received and we retry first chunk.
             */
            val rangeHeader = request.getHeaderField("Range")
            if (rangeHeader != null) {
                val lastReceivedByte = rangeHeader.split("-")[1].toLong()
                if (lastReceivedByte == beginningOfChunk + chunkSize - 1) {
                    chunksUploaded += 1
                    beginningOfChunk = (chunksUploaded * chunkSize)
                }
            }

        } while (request.responseCode != HttpURLConnection.HTTP_OK
            && request.responseCode != HttpURLConnection.HTTP_CREATED
        )

        filesProcessed++
        setRestoreButtonText(RestoreStatus.UPLOADING_FILES)
    }


    /**
     * Returns the most recent Uri the user granted us permission to.
     * Alternatively, returns null if user hasn't yet chosen a directory,
     * the chosen directory has been deleted, or the Uri permission has been revoked.
     */
    private fun verifyAndFetchRootDirectoryUri(): Uri? {
        val savedUriString = requireActivity().getPreferences(Context.MODE_PRIVATE)
            .getString(KEY_ROOT_DIRECTORY_URI, null) ?: return null

        val persistedUriPermissions = requireActivity().contentResolver.persistedUriPermissions

        // Iterate through persistedUriPermissions list to find one that matches our conditions.
        persistedUriPermissions.map { uriPermission ->
            val savedUriMatchesPersistedUri = uriPermission.uri.toString() == savedUriString
            val persistedUri = Uri.parse(savedUriString)
            if (savedUriMatchesPersistedUri &&
                fileForDocumentTreeUriExists(requireActivity(), persistedUri)
            ) {
                return persistedUri
            }
        }
        return null
    }


    private fun updateRootDirectoryUri(uri: Uri?) {
        uri?.let {
            rootDirectoryDocumentFile = DocumentFile.fromTreeUri(requireActivity(), uri)!!
            viewModel.updateRootDirectoryUri(uri)
        }
    }


    private fun promptForPermissionsInSAF(resultLauncher: ActivityResultLauncher<Intent>) {
        val permissionIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        resultLauncher.launch(permissionIntent)
    }


    private suspend fun setBackupButtonText(backupStatus: BackupStatus, completionPercentage: Int? = null) {
        withContext(Dispatchers.Main) {
            val suffix = when (backupStatus) {
                BackupStatus.INACTIVE -> ""
                BackupStatus.DELETING_OLD_FILES -> "Deleting old backup files..."
                BackupStatus.FETCHING_ROOT_DIRECTORY_ID -> "Fetching root directory id..."
                BackupStatus.DOWNLOADING_DIRECTORY_INFO -> "Directories downloaded: $filesProcessed"
                BackupStatus.CREATING_DIRECTORIES -> "Directories created: $filesProcessed"
                BackupStatus.DOWNLOADING_FILES -> "Files downloaded: $filesProcessed\n" +
                        (if (completionPercentage == null) "" else "Current file: $completionPercentage% complete")
            }

            binding.backupButton.text = requireActivity().getString(R.string.backup_button_text, suffix).trim()
        }
    }


    private suspend fun setRestoreButtonText(newRestoreStatus: RestoreStatus, completionPercentage: Int? = null) {
        withContext(Dispatchers.Main) {
            val suffix = when (newRestoreStatus) {
                RestoreStatus.INACTIVE -> ""
                RestoreStatus.UPLOADING_FILES -> "Files uploaded: $filesProcessed\n" +
                        (if (completionPercentage == null) "" else "Current file: $completionPercentage% complete")
            }

            binding.restoreButton.text = requireActivity().getString(R.string.restore_button_text, suffix).trim()
        }
    }


    // Uses https://developers.google.com/identity/sign-in/android/start
    private fun getGoogleSignInClient(): GoogleSignInClient {
        val signInOptions = GoogleSignInOptions
            .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()

        return GoogleSignIn.getClient(requireActivity(), signInOptions);
    }


    // Brings up prompt for user to select Google Account with which to sign in.
    private fun showSignInPrompt(resultLauncher: ActivityResultLauncher<Intent>) {
        val signInIntent = getGoogleSignInClient().signInIntent
        resultLauncher.launch(signInIntent)
    }


    // This gets called after a user has selected a Google Account and signed into it.
    private fun handleSignInPromptResult(data: Intent) {
        try {
            val getAccountTask = GoogleSignIn.getSignedInAccountFromIntent(data)
            val googleAccount = getAccountTask.getResult(ApiException::class.java)
            viewModel.updateUserGoogleSignInAccount(googleAccount)
            getDriveService(googleAccount)
        } catch (e: ApiException) {
            // The ApiException status code indicates the detailed failure reason.
            // Please refer to the GoogleSignInStatusCodes class reference for more information.
            ("signInResult:failed code=" + e.statusCode).print()
        }
    }


    private fun getDriveService(googleAccount: GoogleSignInAccount?) {
        val credential = GoogleAccountCredential.usingOAuth2(
            requireActivity(), listOf(DriveScopes.DRIVE)
        )
        credential.selectedAccount = googleAccount?.account
        val googleDriveService = Drive.Builder(
            NetHttpTransport(),
            JacksonFactory.getDefaultInstance(),
            credential
        ).setApplicationName(getString(R.string.app_name))
            .build()

        this.credential = credential
        viewModel.updateGoogleDriveService(googleDriveService)
    }


    private fun signOut() {
        lifecycleScope.launch {
            try {
                val signOutTask = getGoogleSignInClient().signOut()
                signOutTask.await()
                // Successfully signed out.
                viewModel.updateUserGoogleSignInAccount(null)
                viewModel.updateGoogleDriveService(null)
                Toast.makeText(requireActivity(), " Signed out ", Toast.LENGTH_SHORT).show()
            } catch (throwable: Throwable) {
                Toast.makeText(requireActivity(), " Error ", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        const val TAG_KOTLIN = "TAG_KOTLIN"
        private const val KEY_ROOT_DIRECTORY_URI = "root-uri"
        private const val BACKUP_DIRECTORY = "backup_directory"

        // See here for mimetype info: https://developers.google.com/drive/api/v3/about-files#type
        private const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val GOOGLE_WORKSPACE_FILE_MIME_TYPE_PREFIX = "application/vnd.google-apps"
        private const val UNKNOWN_FILE_MIME_TYPE = "application/octet-stream"
        private const val THIRD_PARTY_SHORTCUT_MIME_TYPE = "application/vnd.google-apps.drive-sdk"
        private const val SHORTCUT_MIME_TYPE = "application/vnd.google-apps.shortcut"
        private const val FIVE_MEGABYTES_IN_BYTES = 5242880
        private const val DOWNLOAD_CHUNK_SIZE_IN_BYTES = 33554432
    }
}


fun Any.print() {
    Log.d(DriveFragment.TAG_KOTLIN, " $this")
}
