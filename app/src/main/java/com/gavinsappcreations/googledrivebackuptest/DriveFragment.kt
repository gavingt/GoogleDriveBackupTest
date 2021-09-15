package com.gavinsappcreations.googledrivebackuptest

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.UriPermission
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

// TODO: Fix case when internet cuts out in middle of an operation (WorkManager?
// TODO: Use different OutputStream? Maybe others allow resuming downloads or have less overhead
// TODO: Cache a map of folder_id => folder_name or similar to preserve hierarchy. Or use this?: val map: Pair<DocumentFile?, String> = rootDirectoryDocumentFile.to("directoryName")

// TODO: add logs for everything
// TODO: Use this instead backing up Google Docs files:  googleDriveService.files().export()

class DriveFragment : Fragment() {

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

        // Here we define what to do with the sign-in result.
        val googleSignInResultLauncher: ActivityResultLauncher<Intent> =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val data: Intent? = result.data
                    handleSignInData(data)
                }
            }

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

        updateRootDirectoryUri(fetchPersistedRootDirectoryUri())

        updateUserGoogleSignInStatus()

        binding.logInButton.setOnClickListener {
            startGoogleSignIn(googleSignInResultLauncher)
        }

        binding.logOutButton.setOnClickListener {
            signOut()
        }

        binding.grantUsbPermissionsButton.setOnClickListener {
            promptForPermissionsInSAF(permissionsLauncher)
        }

        binding.backupButton.setOnClickListener {
            rootDirectoryDocumentFile?.let {
                copyFilesFromGoogleDriveToLocalDirectory()
            }
        }

        return binding.root
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.viewState.asLiveData().observe(viewLifecycleOwner) {
            binding.logInButton.isEnabled = !it.isUserSignedIn
            binding.logOutButton.isEnabled = it.isUserSignedIn
            binding.backupButton.isEnabled = it.isUserSignedIn && it.rootDirectoryUri != null
            binding.restoreButton.isEnabled = it.isUserSignedIn && it.rootDirectoryUri != null

            binding.grantUsbPermissionsButton.text = when (it.rootDirectoryUri) {
                null -> "Grant USB drive permissions"
                else -> "Grant USB drive permissions for different directory"
            }
        }
    }

     /**
      * Returns the most recent Uri the user granted us permission to,
      *  or null if user hasn't yet chosen a directory or if the permission has been revoked.
      */
    private fun fetchPersistedRootDirectoryUri(): Uri? {
        val mostRecentlyGrantedUriString = requireActivity().getPreferences(Context.MODE_PRIVATE)
            .getString(KEY_ROOT_DIRECTORY_URI, null)

        mostRecentlyGrantedUriString?.let {
            val previouslyGrantedPermissions: MutableList<UriPermission> = requireActivity().contentResolver.persistedUriPermissions
            previouslyGrantedPermissions.map {
                if (it.uri.toString() == mostRecentlyGrantedUriString) {
                    return Uri.parse(mostRecentlyGrantedUriString)
                }
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


    private fun updateUserGoogleSignInStatus() {
        viewModel.updateUserGoogleSignInStatus(GoogleSignIn.getLastSignedInAccount(requireActivity()) != null)
    }


    private fun promptForPermissionsInSAF(resultLauncher: ActivityResultLauncher<Intent>) {
        val permissionIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        permissionIntent.addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION
                    or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
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


    private fun getOrCreateFolder(parent: DocumentFile, name: String): DocumentFile? {
        return parent.findFile(name) ?: parent.createDirectory(name)
    }


    private fun copyFilesFromGoogleDriveToLocalDirectory() {

        val backupDirectoryDocumentFile = getOrCreateFolder(rootDirectoryDocumentFile!!, BACKUP_DIRECTORY)

        getDriveService()?.let { googleDriveService ->

            lifecycleScope.launch(Dispatchers.IO) {
                var pageToken: String? = null
                do {
                    val result = googleDriveService.files().list().apply {
                        spaces = "drive"
                        fields = "nextPageToken, files(id, name, mimeType)"
                        this.pageToken = pageToken
                    }.execute()

                    for (file: File in result.files) {

                        (file.name).print()
                        (file.mimeType).print()

                        // TODO: For now we're just skipping over files from Google Apps since they need to be handled differently
                        if (file.mimeType.startsWith(GOOGLE_APP_FILE_MIME_TYPE_PREFIX)) {
                            continue
                        }

                        when (file.isDirectory()) {
                            true -> {
                                // TODO: if folder has a parent, call createDirectory() to make it.
                                if (file.parents != null && file.parents.size != 0) {
                                    val test: String = file.parents[0]
                                    (test).print()
                                }
                                backupDirectoryDocumentFile!!.createDirectory(file.name)
                            }
                            false -> {
                                // TODO: call createFile() on proper directory, not always backupDirectory
                                val currentDocumentFile = backupDirectoryDocumentFile!!.createFile(file.mimeType, file.name)
                                currentDocumentFile?.let {

                                    val outputStream = requireActivity().contentResolver.openOutputStream(currentDocumentFile.uri)!!
                                    googleDriveService.files().get(file.id).executeMediaAndDownloadTo(outputStream)
                                    outputStream.close()
                                }
                            }
                        }
                    }
                    pageToken = result.nextPageToken
                } while (pageToken != null)
            }
        }
    }


    private fun File.isDirectory() = mimeType == DRIVE_FOLDER_MIME_TYPE


    private fun copyFilesFromLocalDirectoryToGoogleDrive() {
        // https://developers.google.com/drive/api/v3/folder
        // https://commonsware.com/blog/2019/11/09/scoped-storage-stories-trees.html
    }


    private fun getGoogleSignInClient(): GoogleSignInClient {
        /**
         * Configure sign-in to request the user's ID, email address, and basic
         * profile. ID and basic profile are included in DEFAULT_SIGN_IN.
         */
        val signInOptions = GoogleSignInOptions
            .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            // Ask for permission to modify everything on user's Drive.
            // TODO: only request scope needed for current task - DRIVE for writing and DRIVE_READONLY for reading
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()


        // Build a GoogleSignInClient with the options specified by our signInOptions object.
        return GoogleSignIn.getClient(requireActivity(), signInOptions);
    }

    private fun startGoogleSignIn(resultLauncher: ActivityResultLauncher<Intent>) {
        val signInIntent = getGoogleSignInClient().signInIntent
        resultLauncher.launch(signInIntent)
    }


    private fun signOut() {
        lifecycleScope.launch {
            try {
                val signOutTask = getGoogleSignInClient().signOut()
                signOutTask.await()
                // Successfully signed out.
                updateUserGoogleSignInStatus()
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
            updateUserGoogleSignInStatus()
            "account ${account.account}".print()
            "displayName ${account.displayName}".print()
            "Email ${account.email}".print()
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
