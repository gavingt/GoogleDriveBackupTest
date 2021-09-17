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
import com.gavinsappcreations.googledrivebackuptest.State.Failed
import com.gavinsappcreations.googledrivebackuptest.State.Success
import com.gavinsappcreations.googledrivebackuptest.databinding.FragmentDriveBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.util.*

// TODO: Use WorkManager to disconnect the backup/restore processes from the UI.
// TODO: Handle case when user logs out
// TODO: Use this instead for backing up Google Docs files: googleDriveService.files().export()
// TODO: should we copy files that were "shared with me"?
// TODO: do we need to worry about a file having multiple parents? Right now we're just using first parent.
// TODO: Use DocumentsContract.build... methods if I need more specific operations.

// TODO: add logs for everything

// TODO: download metadata like total # of folders, total # files, total size of Drive before starting backup process.

// TODO: Add cancel option (by pressing backupButton while backup is in progress)
// TODO: fix method for ending recursive function


class DriveFragment : Fragment() {

    //private val directorySet: MutableSet<DirectoryInfoContainer> = mutableSetOf()

    // Map whose key = parent directory and value = list of subdirectories inside the parent directory.
    private val directoryMap: MutableMap<String, List<DirectoryInfoContainer>> = mutableMapOf()

    var iterations = 0

    var filesProcessed = 0

    var lastEventTime: Long = 0

    // TODO: get rid of DocumentFile usage
    var rootDirectoryDocumentFile: DocumentFile? = null

    private val viewModel by viewModels<DriveViewModel>()
    private lateinit var binding: FragmentDriveBinding


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
                    val data: Intent? = result.data
                    handleSignInData(data)
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
        updateUserGoogleSignInStatus()

        binding.logInButton.setOnClickListener {
            if (viewModel.viewState.value.isUserSignedIn) {
                signOut()
            } else {
                startGoogleSignIn(googleSignInResultLauncher)
            }
        }

        binding.grantUsbPermissionsButton.setOnClickListener {
            promptForPermissionsInSAF(permissionsLauncher)
        }

        binding.backupButton.setOnClickListener {
            startBackupProcedure()
        }

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Here we observe and react to changes in our view's state, which is stored in our ViewModel.
        viewModel.viewState.asLiveData().observe(viewLifecycleOwner) { state ->
            binding.backupButton.isEnabled = state.isUserSignedIn && state.rootDirectoryUri != null
            // TODO: remove hard-coded "false" before working on restore feature
            binding.restoreButton.isEnabled = false /*state.isUserSignedIn && state.rootDirectoryUri != null*/
            binding.progressBar.visibility = if (state.isBackupInProgress) View.VISIBLE else View.GONE

            binding.logInButton.text = when (state.isUserSignedIn) {
                true -> "Logged in as ${state.userEmailAddress}.\n\nLog out?"
                false -> "Log into Google account"
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

        viewModel.updateIsBackupInProgress(true)

        lifecycleScope.launch(Dispatchers.IO) {

            deleteAllFilesInBackupDirectory()

            val googleDriveRootDirectoryId = fetchGoogleDriveRootDirectoryId()

            val directoryInfo = fetchDirectoryInfo()

            if (googleDriveRootDirectoryId is Success && directoryInfo is Success) {
                val backupDirectoryUri = getOrCreateDirectory(rootDirectoryDocumentFile!!.uri, BACKUP_DIRECTORY)!!
                withContext(Dispatchers.Default) {
                    iterations = 0
                    directoryMap.clear()
                    directoryMap.putAll(directoryInfo.data)
                    filesProcessed = 0

                    createDirectoryStructure(googleDriveRootDirectoryId.data, backupDirectoryUri)
                }
            } else {
                viewModel.updateUserGoogleSignInStatus(false)
            }
        }
    }


    /**
     * For testing purposes, delete all current files in backup directory before starting. This
     * only deletes files in the "backup_directory" folder we create, so it won't delete your personal files
     */
    private fun deleteAllFilesInBackupDirectory() {
        val listOfBackupDirectoryFiles = rootDirectoryDocumentFile!!.listFiles()
        for (file in listOfBackupDirectoryFiles) {
            file.delete()
        }
    }


    private suspend fun fetchGoogleDriveRootDirectoryId(): State<String> {

        withContext(Dispatchers.Main) {
            setBackupButtonText(OperationType.FETCHING_ROOT_DIRECTORY_ID)
        }

        try {
            getDriveService()?.let { googleDriveService ->
                // TODO: wrap in try-catch, especially since IOException can occur from network issues
                val rootIdResult = lifecycleScope.async(Dispatchers.IO) {
                    val result = googleDriveService.files().get("root").apply {
                        fields = "id"
                    }.execute()

                    withContext(Dispatchers.Main) {
                        ("Found root directoryId: ${result.id}").print()
                    }

                    return@async result.id
                }

                return Success(rootIdResult.await())
            }
            return Failed(Exception("Not logged into Google Drive account"))
        } catch (throwable: Throwable) {
            return Failed(throwable)
        }
    }


    private suspend fun fetchDirectoryInfo(): State<MutableMap<String, MutableList<DirectoryInfoContainer>>> {

        val directoryMap: MutableMap<String, MutableList<DirectoryInfoContainer>> = mutableMapOf()
        filesProcessed = 0

        try {
            getDriveService()?.let { googleDriveService ->
                val directoryInfoResult = lifecycleScope.async(Dispatchers.IO) {
                    var pageToken: String? = null
                    do {
                        // TODO: wrap in try-catch, especially since IOException can occur from network issues
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
                                directoryMap[parentUri] = mutableListOf(DirectoryInfoContainer(file.id, file.name, null))
                            } else {
                                directoryMap[parentUri]!!.add(DirectoryInfoContainer(file.id, file.name, null))
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
            }
            return Failed(Exception("Not logged into Google Drive account"))
        } catch (throwable: Throwable) {
            return Failed(throwable)
        }
    }


    // TODO: what if I make reference to coroutineJob and cancel it at end?
    // TODO: can I exploit structured concurrency to wait for all recursive functions to end?
    // TODO: filesProcessed number is now reported incorrectly by this method.
    private suspend fun createDirectoryStructure(directoryBeingBuiltId: String, directoryBeingBuiltUri: Uri) {

        lastEventTime = System.currentTimeMillis()

        // Create subdirectories for all children of directoryBeingBuilt.
        directoryMap[directoryBeingBuiltId]?.forEach { childDirectory ->

            // Create a directory for each childDirectory and fetch its Uri.
            val currentSubdirectoryUri = getOrCreateDirectory(directoryBeingBuiltUri, childDirectory.directoryName)!!

            childDirectory.directoryUri = currentSubdirectoryUri

            // Set the subdirectory we just built as directoryBeingBuilt and re-run this method.
            //lifecycleScope.launch(Dispatchers.Default) {
                createDirectoryStructure(childDirectory.directoryId, currentSubdirectoryUri)
                filesProcessed++
           // }
        }

        withContext(Dispatchers.Main) {
            iterations++
            "iterations: $iterations".print()
            setBackupButtonText(OperationType.CREATING_DIRECTORIES)
        }


        // TODO: find better way to detect end of recursive operation.

        val nullUrisRemain = directoryMap.any { map ->
            map.value.any { list ->
                list.directoryUri == null
            }
        }

        if (!nullUrisRemain) {
            withContext(Dispatchers.IO) {
                copyFilesFromGoogleDriveToLocalDirectory(directoryMap)
            }
        }
    }


    private fun copyFilesFromGoogleDriveToLocalDirectory(directoryMap: MutableMap<String, List<DirectoryInfoContainer>>) {

        filesProcessed = 0

        val directorySet = mutableSetOf<DirectoryInfoContainerFull>()

        directoryMap.forEach {
            for (entry in it.value) {
                directorySet.add(DirectoryInfoContainerFull(entry.directoryId, entry.directoryName, entry.directoryUri, it.key))
            }
        }



        getDriveService()?.let { googleDriveService ->

            fun fetchParentUriFromParentId(parentId: String): Uri? {
                val matchingDirectoryInfoContainer = directorySet.find {
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

                // TODO: NPE here now.
                val parentUri = fetchParentUriFromParentId(parentId) ?: rootDirectoryDocumentFile!!.uri

                // TODO: get rid of DocumentFile
                val parentDocumentFile = DocumentFile.fromTreeUri(requireActivity(), parentUri)
                val localDocumentFile = parentDocumentFile!!.createFile(file.mimeType, file.name)

                localDocumentFile?.let {
                    // TODO: wrap in try-catch, especially since IOException can occur from network issues
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
                        // Select files that are not folders, aren't in trash, and which user owns.
                        q = "mimeType != 'application/vnd.google-apps.folder' and trashed = false and 'me' in owners"
                        fields = "nextPageToken, files(id, name, mimeType, parents)"
                        this.pageToken = pageToken
                    }.execute()

                    for (file in result.files) {
                        (file.name).print()
                        (file.mimeType).print()
                        (file.parents ?: "null").print()

                        // TODO: For now we're just skipping over files from Google Apps since they need to be handled differently
                        if (file.mimeType != DRIVE_FOLDER_MIME_TYPE && file.mimeType.startsWith(GOOGLE_APP_FILE_MIME_TYPE_PREFIX)) {
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


    private fun updateUserGoogleSignInStatus() {
        val signedInAccount = GoogleSignIn.getLastSignedInAccount(requireActivity())

        viewModel.updateUserGoogleSignInStatus(signedInAccount != null)

        signedInAccount?.let {
            viewModel.updateUserEmailAddress(signedInAccount.email)
        }
    }


    private fun promptForPermissionsInSAF(resultLauncher: ActivityResultLauncher<Intent>) {
        val permissionIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        resultLauncher.launch(permissionIntent)
    }


    private fun getDriveService(): Drive? {
        GoogleSignIn.getLastSignedInAccount(requireActivity())?.let { googleAccount ->
            val credential = GoogleAccountCredential.usingOAuth2(
                requireActivity(), listOf(DriveScopes.DRIVE)
            )

            credential.selectedAccount = googleAccount.account!!
            return Drive.Builder(
                NetHttpTransport(),
                JacksonFactory.getDefaultInstance(),
                credential
            )
                .setApplicationName(getString(R.string.app_name))
                .build()
        }
        return null
    }


    private fun getOrCreateDirectory(parentUri: Uri, name: String): Uri? {
        return DocumentsContract.createDocument(
            requireActivity().contentResolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)

    }


    private fun setBackupButtonText(operationType: OperationType) {
        val suffix = when (operationType) {
            OperationType.FETCHING_ROOT_DIRECTORY_ID -> "Fetching root directory id..."
            OperationType.DOWNLOADING_DIRECTORIES -> "Directories downloaded: $filesProcessed"
            OperationType.CREATING_DIRECTORIES -> "Directories created: $filesProcessed"
            OperationType.DOWNLOADING_FILES -> "Files downloaded: $filesProcessed"
            OperationType.BACKUP_COMPLETE -> "Backup complete"
        }

        binding.backupButton.text = requireActivity().getString(R.string.backup_button_text, suffix)
    }


    private fun copyFilesFromLocalDirectoryToGoogleDrive() {
        // https://developers.google.com/drive/api/v3/folder
        // https://commonsware.com/blog/2019/11/09/scoped-storage-stories-trees.html

/*        We can call file.parents = ... to set a file's parents directly:
        val file = File()
        file.parents = listOf("idOfParent1", "idOfParent2")*/
    }


    // Uses https://developers.google.com/identity/sign-in/android/start
    private fun startGoogleSignIn(resultLauncher: ActivityResultLauncher<Intent>) {
        val signInIntent = getGoogleSignInClient().signInIntent
        resultLauncher.launch(signInIntent)
    }

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


    private fun signOut() {
        lifecycleScope.launch {
            try {
                val signOutTask = getGoogleSignInClient().signOut()
                signOutTask.await()
                // Successfully signed out.
                updateUserGoogleSignInStatus()
                viewModel.updateUserEmailAddress(null)
                Toast.makeText(requireActivity(), " Signed out ", Toast.LENGTH_SHORT).show()
            } catch (throwable: Throwable) {
                Toast.makeText(requireActivity(), " Error ", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun handleSignInData(data: Intent?) {
        val getAccountTask = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = getAccountTask.getResult(ApiException::class.java)
            // User signed in successfully.
            viewModel.updateUserEmailAddress(account.email)
            updateUserGoogleSignInStatus()
            "Scopes granted ${account.grantedScopes}".print()
        } catch (e: ApiException) {
            // The ApiException status code indicates the detailed failure reason.
            // Please refer to the GoogleSignInStatusCodes class reference for more information.
            Log.w(TAG_KOTLIN, "signInResult:failed code=" + e.statusCode)
            // TODO: update the UI to display some sort of failure state.
            //updateUI(null)
        }
    }


    companion object {
        const val TAG_KOTLIN = "TAG_KOTLIN"
        private const val KEY_ROOT_DIRECTORY_URI = "root-uri"
        private const val BACKUP_DIRECTORY = "backup_directory"

        private const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val GOOGLE_DOC_FILE_MIME_TYPE = "application/vnd.google-apps.document"
        private const val GOOGLE_APP_FILE_MIME_TYPE_PREFIX = "application/vnd.google-apps"
        private const val UNKNOWN_FILE_MIME_TYPE = "application/octet-stream"
    }
}


fun Any.print() {
    Log.d(DriveFragment.TAG_KOTLIN, " $this")
}
