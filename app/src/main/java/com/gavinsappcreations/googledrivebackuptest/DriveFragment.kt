package com.gavinsappcreations.googledrivebackuptest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
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
import com.gavinsappcreations.googledrivebackuptest.State.*
import com.gavinsappcreations.googledrivebackuptest.databinding.FragmentDriveBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.media.MediaHttpUploader
import com.google.api.client.http.FileContent
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpResponse
import com.google.api.client.http.InputStreamContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.io.*
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

// TODO: get rid of DocumentFiles usages, especially listFiles() when I don't need all the files it returns (use buildChildDocumentsUriUsingTree() to fetch a URI that we can query to get child URIs)

// TODO: Handle case when user logs out.
// TODO: Should we copy files that were "shared with me"?
// TODO: Do we need to worry about a file having multiple parents? Right now we're just using first parent.
// TODO: how do I handle changes to Google Drive files mid-backup?: https://developers.google.com/drive/api/v3/reference/changes

// TODO: Create the directory on demand as I'm creating the file.
// TODO: Download metadata like total # of folders, total # files, total size of Drive,
//       and total number of each filetype (photo/video/audio/document/others) before starting backup process.
//       Do this all at once instead of splitting it up into folders and then files.
// TODO: Add cancel option (by pressing backupButton while backup is in progress).
// TODO: Use WorkManager in ForegroundService mode to disconnect the backup/restore processes from the UI?:
//       https://www.raywenderlich.com/20689637-scheduling-tasks-with-android-workmanager
// TODO: make repo private (make private and then go to "Manage access" in left pane of Settings screen to invite people.


@Suppress("BlockingMethodInNonBlockingContext")
class DriveFragment : Fragment() {

    // Map of directories whose key = parent directory id and value = list of child directories inside the parent directory.
    // If a directory doesn't have any child directories, it won't be listed as a key.
    private val directoryMap: MutableMap<String, List<SubdirectoryContainer>> = mutableMapOf()

    var iterations = 0

    var filesProcessed = 0

    // TODO: get rid of DocumentFile usage
    var rootDirectoryDocumentFile: DocumentFile? = null

    private val viewModel by viewModels<DriveViewModel>()
    private lateinit var binding: FragmentDriveBinding

    private lateinit var uriToUpload: Uri

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

        // Here we specify what to do with the Uri of the file we chose to upload
        val pickFileToUploadLauncher: ActivityResultLauncher<Intent> =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val uri = result.data!!.data!!
                    uriToUpload = uri
                    Toast.makeText(requireActivity(), "Uri of file to upload: $uri", Toast.LENGTH_SHORT).show()

                } else {
                    Toast.makeText(requireActivity(), "pick file to upload request cancelled", Toast.LENGTH_SHORT).show()
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
                uploadResumableFileMetadata()
                //uploadMultipartFile()
                //startRestoreProcedure(rootDirectoryDocumentFile!!, "root")
                requireActivity().externalCacheDir?.deleteRecursively()
            }
        }

        binding.pickFileToUploadButton.setOnClickListener {
            pickFileToUpload(pickFileToUploadLauncher)
        }

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Here we observe and react to changes in our view's state, which is stored in our ViewModel.
        viewModel.viewState.asLiveData().observe(viewLifecycleOwner) { state ->

            val isBackupAndRestoreEnabled =
                state.googleSignInAccount != null && state.googleDriveService != null && state.rootDirectoryUri != null

            binding.backupButton.isEnabled = isBackupAndRestoreEnabled
            binding.restoreButton.isEnabled = isBackupAndRestoreEnabled
            binding.progressBar.visibility = if (state.isBackupInProgress) View.VISIBLE else View.GONE

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

    //TODO: refined restore procedure:
    //     1) enumerate all local files and folders
    //     2) enumerate all files/folders on Google Drive
    //     3) create list of all files contained locally but not on Google Drive.
    //     4) measure size of list created in step 3 (use getFilesToUploadSize() method below)
    //     5) check free space and remaining daily usage on Google Drive and ensure that list generated in step 3 can fit within it: https://developers.google.com/drive/api/v3/reference/about/
    //     6) upload list from step 3

    // TODO: add UI for uploading (including percent complete)
    private suspend fun startRestoreProcedure(directoryBeingUploadedDocumentFile: DocumentFile, directoryBeingUploadedId: String) {

        coroutineScope {
            val driveService = viewModel.viewState.value.googleDriveService!!

            val listOfFiles = directoryBeingUploadedDocumentFile.listFiles()

            for (documentFileToUpload in listOfFiles) {
                if (documentFileToUpload.isDirectory) {
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

                    // If we're creating a directory, run this method recursively to create files inside the directory too.
                    launch {
                        startRestoreProcedure(documentFileToUpload, directoryToUpload.id)
                    }
                } else {

                    val tempFile = createTempFile(documentFileToUpload)

                    // Stores metadata about the file being uploaded.
                    val fileMetadata = File().apply {
                        name = documentFileToUpload.name
                        mimeType = documentFileToUpload.type
                        parents = Collections.singletonList(directoryBeingUploadedId)
                    }

                    // Stores the actual file content being uploaded.
                    val mediaContent = FileContent("image/jpeg", tempFile)

                    val fileToUpload = driveService.files().create(fileMetadata, mediaContent)
                        .setFields("id, name")
                        .execute()

                    withContext(Dispatchers.Main) {
                        ("Upload file with name: ${fileToUpload.name}, ID: ${fileToUpload.id}").print()
                    }
                }
            }
        }
    }


    /**
     * We only have a Uri pointing to the backup directory, but we need a java.io.File
     * in order to upload a document to Google Drive. So we use streams to create a
     * temporary java.io.File in externalCacheDir for each document.
     */
    private fun createTempFile(documentFile: DocumentFile): java.io.File {
        val tempFile = java.io.File(requireActivity().externalCacheDir, documentFile.name ?: "tempFileName")
        tempFile.createNewFile()
        val inputStream = requireActivity().contentResolver.openInputStream(documentFile.uri)!!
        val outputStream = FileOutputStream(tempFile)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()
        return tempFile
    }


    private fun uploadMultipartFile() {

        val googleDriveService = viewModel.viewState.value.googleDriveService!!


        val mediaFile = createTempFile(rootDirectoryDocumentFile!!.listFiles()[0])

        val fileMetadata = File().apply {
            name = "Movie.mp4"
        }
        val mediaContent = InputStreamContent(
            "video/mp4",
            BufferedInputStream(FileInputStream(mediaFile))
        )
        mediaContent.length = mediaFile.length()
        val file: HttpResponse = googleDriveService.files().create(fileMetadata, mediaContent)
            .setFields("id")
            .mediaHttpUploader
            .setChunkSize(MediaHttpUploader.MINIMUM_CHUNK_SIZE)
            .setProgressListener {
                /*                switch (uploader.getUploadState()) {
                    case INITIATION_STARTED:
                    System.out.println("Initiation Started");
                    break;
                    case INITIATION_COMPLETE:
                    System.out.println("Initiation Completed");
                    break;
                    case MEDIA_IN_PROGRESS:
                    System.out.println("Upload in progress");
                    System.out.println("Upload percentage: " + uploader.getProgress());
                    break;
                    case MEDIA_COMPLETE:
                    System.out.println("Upload Completed!");
                    break;*/

                if (it.uploadState == MediaHttpUploader.UploadState.MEDIA_IN_PROGRESS) {
                    ("${it.progress * 100}%").print()
                }


            }

            .setDirectUploadEnabled(false)
            .upload(GenericUrl("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"))
        println(file.statusMessage)
    }


    // https://stackoverflow.com/a/62155631/7434090
    private fun uploadResumableFileMetadata() {

        val mediaDocumentFile = rootDirectoryDocumentFile!!.listFiles()[0]
        val mimeType = mediaDocumentFile.type ?: UNKNOWN_FILE_MIME_TYPE

        val mediaFile = createTempFile(mediaDocumentFile)
        val fileSizeInBytes = getFileSizeInBytes(mediaFile)

        val requestUrl = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable")

        val requestBody = "{\"name\": \"Photo.jpg\"}"

        val request: HttpURLConnection = requestUrl.openConnection() as HttpURLConnection

        request.requestMethod = "POST"
        request.doInput = true
        request.doOutput = true
        request.setRequestProperty("Authorization", "Bearer " + credential.token)
        request.setRequestProperty("X-Upload-Content-Type", mimeType)
        request.setRequestProperty("X-Upload-Content-Length", fileSizeInBytes.toString())
        request.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        request.setRequestProperty("Content-Length", java.lang.String.format(Locale.ENGLISH, "%d", requestBody.toByteArray().size))

        val outputStream: OutputStream = request.outputStream
        outputStream.write(requestBody.toByteArray())
        outputStream.close()

        request.connect()

        val responseCode = request.responseCode
        ("Upload request response code: $responseCode").print()


        if (request.responseCode == HttpURLConnection.HTTP_OK) {
            // TODO: save this sessionUri somewhere until file is completely uploaded
            val sessionUri = URL(request.getHeaderField("location"))
            uploadResumableFileContent(sessionUri, fileSizeInBytes, mediaFile, mimeType)
        } else {
            // TODO: retry?
        }
    }


    private fun uploadResumableFileContent(sessionUri: URL, fileSizeInBytes: Int, fileToUpload: java.io.File, mimeType: String) {
        // set these variables:
        var beginningOfChunk: Long = 0
        var chunkSize = (2 * MediaHttpUploader.MINIMUM_CHUNK_SIZE).toLong()
        var chunksUploaded = 0

        // Here starts the upload chunk code:
        do {
            val request = sessionUri.openConnection() as HttpURLConnection

            request.requestMethod = "PUT"
            request.doOutput = true

            // change your timeout as you desire here:
            request.connectTimeout = 30000
            request.setRequestProperty("Content-Type", mimeType)

            val bytesUploadedSoFar = chunksUploaded * chunkSize
            bytesUploadedSoFar.print()

            if (beginningOfChunk + chunkSize > fileSizeInBytes) {
                chunkSize = fileSizeInBytes - beginningOfChunk
            }

            request.setRequestProperty("Content-Length", java.lang.String.format(Locale.ENGLISH, "%d", chunkSize))
            request.setRequestProperty("Content-Range", "bytes " + beginningOfChunk + "-" + (beginningOfChunk + chunkSize - 1) + "/" + fileSizeInBytes)

            ("Content-Range: $beginningOfChunk - ${(beginningOfChunk + chunkSize - 1)} / $fileSizeInBytes").print()

            val buffer = ByteArray(chunkSize.toInt())
            val fileInputStream = FileInputStream(fileToUpload)
            fileInputStream.channel.position(beginningOfChunk)
            fileInputStream.close()

            val outputStream = request.outputStream
            outputStream.write(buffer)
            outputStream.close()
            request.connect()

            request.responseCode.print()
            request.responseMessage.print()

            ("Range header: ${request.getHeaderField("Range")}").print()

            val rangeHeader = request.getHeaderField("Range")
            if (rangeHeader != null) {
                val lastReceivedByte = rangeHeader.split("-")[1].toLong()
                if (lastReceivedByte == beginningOfChunk + chunkSize - 1) {
                    chunksUploaded += 1
                    beginningOfChunk = (chunksUploaded * chunkSize)
                }
            }

        } while (request.responseCode != HttpURLConnection.HTTP_OK && request.responseCode != HttpURLConnection.HTTP_CREATED && bytesUploadedSoFar < fileSizeInBytes)

        // End of upload chunk section
    }


    fun getFilesToUploadSize(filesToBeUploaded: List<java.io.File>): Long {
        var totalLength: Long = 0

        for (fileToUpload in filesToBeUploaded) {
            totalLength += fileToUpload.length()
        }

        return totalLength
    }

    private fun getFileSizeInBytes(file: java.io.File): Int {
        return java.lang.String.valueOf(file.length()).toInt()
    }


    private fun startBackupProcedure() {

        viewModel.updateIsBackupInProgress(true)

        lifecycleScope.launch(Dispatchers.IO) {

            deleteAllFilesInBackupDirectory()

            val googleDriveRootDirectoryId = fetchGoogleDriveRootDirectoryId()

            val directoryInfo = fetchDirectoryInfo()

            if (googleDriveRootDirectoryId is Success && directoryInfo is Success) {
                val backupDirectoryUri = getOrCreateDirectory(rootDirectoryDocumentFile!!.uri, BACKUP_DIRECTORY)!!

                iterations = 0
                directoryMap.clear()
                directoryMap.putAll(directoryInfo.data)
                filesProcessed = 0

                createDirectoryStructure(googleDriveRootDirectoryId.data, backupDirectoryUri)

                copyFilesFromGoogleDriveToLocalDirectory(directoryMap)
            }
        }
    }


    /**
     * For testing purposes, delete all current files in backup directory before starting. This
     * only deletes files in the "backup_directory" folder we create, so it won't delete your personal files
     */
    private suspend fun deleteAllFilesInBackupDirectory() {
        withContext(Dispatchers.Main) {
            setBackupButtonText(OperationType.DELETING_OLD_FILES)
        }
        val listOfBackupDirectoryFiles: Array<DocumentFile> = rootDirectoryDocumentFile!!.listFiles()
        for (file in listOfBackupDirectoryFiles) {
            file.delete()
        }
    }

    // TODO: rather than a network request, in this method we should search directoryMap for the one
    //       directory that doesn't have a parent directory (that's the root)

    private suspend fun fetchGoogleDriveRootDirectoryId(): State<String> {

        withContext(Dispatchers.Main) {
            setBackupButtonText(OperationType.FETCHING_ROOT_DIRECTORY_ID)
        }

        try {
            // TODO: wrap in try-catch, since IOException can occur from network issues
            val rootIdResult = lifecycleScope.async(Dispatchers.IO) {
                val googleDriveService = viewModel.viewState.value.googleDriveService!!
                val result = googleDriveService.files().get("root").apply {
                    fields = "id"
                }.execute()

                withContext(Dispatchers.Main) {
                    ("Found root directoryId: ${result.id}").print()
                }

                return@async result.id
            }

            return Success(rootIdResult.await())
        } catch (throwable: Throwable) {
            return Failed(throwable)
        }
    }


    private suspend fun fetchDirectoryInfo(): State<MutableMap<String, MutableList<SubdirectoryContainer>>> {

        val directoryMap: MutableMap<String, MutableList<SubdirectoryContainer>> = mutableMapOf()
        filesProcessed = 0

        try {
            val directoryInfoResult = lifecycleScope.async(Dispatchers.IO) {
                var pageToken: String? = null
                do {
                    // TODO: wrap in try-catch, since IOException can occur from network issues
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

                        if (directoryMap[parentUri] == null) {
                            directoryMap[parentUri] = mutableListOf(SubdirectoryContainer(file.id, file.name, null))
                        } else {
                            directoryMap[parentUri]!!.add(SubdirectoryContainer(file.id, file.name, null))
                        }

                        withContext(Dispatchers.Main) {
                            filesProcessed++
                            setBackupButtonText(OperationType.DOWNLOADING_DIRECTORIES)
                        }
                    }
                    pageToken = result.nextPageToken
                } while (pageToken != null)

                return@async directoryMap
            }
            return Success(directoryInfoResult.await())
        } catch (throwable: Throwable) {
            return Failed(throwable)
        }
    }


    private suspend fun createDirectoryStructure(directoryBeingBuiltId: String, directoryBeingBuiltUri: Uri) {
        coroutineScope {
            // Create subdirectories for all children of directoryBeingBuilt.
            directoryMap[directoryBeingBuiltId]?.forEach { childDirectory ->

                // Create a directory for each childDirectory and set its Uri in directoryMap.
                val currentSubdirectoryUri = getOrCreateDirectory(directoryBeingBuiltUri, childDirectory.subdirectoryName)!!
                childDirectory.subdirectoryUri = currentSubdirectoryUri

                // Set the subdirectory we just built as directoryBeingBuilt and re-run this method.
                launch {
                    createDirectoryStructure(childDirectory.subdirectoryId, currentSubdirectoryUri)
                    filesProcessed++
                }
            }

            withContext(Dispatchers.Main) {
                iterations++
                "iterations: $iterations".print()
                setBackupButtonText(OperationType.CREATING_DIRECTORIES)
            }
        }
    }

    // TODO: make downloads resumable and show progress indicator (Use HttpResponse like we do in uploadMultipartFile() method)

    private fun copyFilesFromGoogleDriveToLocalDirectory(subdirectoryMap: MutableMap<String, List<SubdirectoryContainer>>) {

        filesProcessed = 0
        val subdirectorySet = mutableSetOf<SubdirectoryContainerWithParent>()
        val googleDriveService = viewModel.viewState.value.googleDriveService!!

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

            // TODO: instead return Failed(Exception("parentUri for file not found")
            return null
        }


        fun createLocalFileFromGoogleDriveFile(file: File) {
            val parentId = file.parents[0]

            val parentUri = fetchParentUriFromParentId(parentId) ?: rootDirectoryDocumentFile!!.uri

            // TODO: get rid of DocumentFile usage
            val parentDocumentFile = DocumentFile.fromTreeUri(requireActivity(), parentUri)
            val localDocumentFile = parentDocumentFile!!.createFile(file.mimeType, file.name)

            localDocumentFile?.let {
                // TODO: wrap in try-catch, since IOException can occur from network issues
                val outputStream = requireActivity().contentResolver.openOutputStream(localDocumentFile.uri)!!
                googleDriveService.files().get(file.id).executeMediaAndDownloadTo(outputStream)
                outputStream.close()
            }
        }

        lifecycleScope.launch(Dispatchers.IO) {
            var pageToken: String? = null
            do {
                val result = googleDriveService.files().list().apply {
                    spaces = "drive"
                    corpora = "user"
                    // Select files that are not folders, aren't in trash, and which user owns.
                    q = "mimeType != 'application/vnd.google-apps.folder' and trashed = false and 'me' in owners"
                    fields = "nextPageToken, files(id, name, mimeType, parents)"
                    this.pageToken = pageToken
                }.execute()

                for (file in result.files) {
                    (file.name).print()
                    (file.mimeType).print()
                    (file.parents ?: "null").print()

                    // TODO: For now we're just skipping over Google Workspace files since they need to be handled differently.
                    //       For these we'll need to use googleDriveService.files().export() instead of googleDriveService.files().get().
                    if (file.mimeType != DRIVE_FOLDER_MIME_TYPE && file.mimeType.startsWith(GOOGLE_WORKSPACE_FILE_MIME_TYPE_PREFIX)) {
                        continue
                    }

                    createLocalFileFromGoogleDriveFile(file)

                    // Temporarily switch back to main thread to update UI
                    withContext(Dispatchers.Main) {
                        filesProcessed++
                        setBackupButtonText(OperationType.DOWNLOADING_FILES)
                    }
                }
                pageToken = result.nextPageToken
            } while (pageToken != null)

            viewModel.updateIsBackupInProgress(false)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireActivity(), "Backup complete", Toast.LENGTH_LONG).show()
                setBackupButtonText(OperationType.BACKUP_COMPLETE)
            }
        }
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
            if (savedUriMatchesPersistedUri && fileForDocumentTreeUriExists(persistedUri)) {
                return persistedUri
            }
        }
        return null
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


    /**
     * Returns boolean indicating whether the provided tree Uri points to a directory that actually exists.
     */
    private fun fileForDocumentTreeUriExists(documentTreeUri: Uri) =
        DocumentFile.fromTreeUri(requireActivity(), documentTreeUri)?.exists() ?: false


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

    private fun pickFileToUpload(resultLauncher: ActivityResultLauncher<Intent>) {
        val pickFileToUploadIntent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        pickFileToUploadIntent.addCategory(Intent.CATEGORY_OPENABLE)
        pickFileToUploadIntent.type = "*/*"
        resultLauncher.launch(pickFileToUploadIntent)
    }


    // TODO: throw IOException
    private suspend fun getOrCreateDirectory(parentUri: Uri, name: String): Uri? = withContext(Dispatchers.IO) {
        // Ignore "Inappropriate blocking call method" warning, as it's a false positive.
        DocumentsContract.createDocument(
            requireActivity().contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name
        )
    }


    private fun setBackupButtonText(operationType: OperationType) {
        val suffix = when (operationType) {
            OperationType.DELETING_OLD_FILES -> "Deleting old backup files..."
            OperationType.FETCHING_ROOT_DIRECTORY_ID -> "Fetching root directory id..."
            OperationType.DOWNLOADING_DIRECTORIES -> "Directories downloaded: $filesProcessed"
            OperationType.CREATING_DIRECTORIES -> "Directories created: $filesProcessed"
            OperationType.DOWNLOADING_FILES -> "Files downloaded: $filesProcessed"
            OperationType.BACKUP_COMPLETE -> "Backup complete!"
        }

        binding.backupButton.text = requireActivity().getString(R.string.backup_button_text, suffix)
    }


    // Uses https://developers.google.com/identity/sign-in/android/start
    private fun getGoogleSignInClient(): GoogleSignInClient {
        val signInOptions = GoogleSignInOptions
            .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            // Ask for permission to modify everything on user's Drive.
            // TODO: only request scope needed for current task - DRIVE for writing and DRIVE_READONLY for reading
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
    }
}


fun Any.print() {
    Log.d(DriveFragment.TAG_KOTLIN, " $this")
}
