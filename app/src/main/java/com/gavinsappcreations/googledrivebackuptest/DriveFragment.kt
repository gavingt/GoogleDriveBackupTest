package com.gavinsappcreations.googledrivebackuptest

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.gavinsappcreations.googledrivebackuptest.databinding.FragmentDriveBinding
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException


class DriveFragment : Fragment() {

    private val viewModel by viewModels<DriveViewModel>()
    private lateinit var binding: FragmentDriveBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(
            inflater, R.layout.fragment_drive, container, false
        )

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel

        // Here we define what to do with the sign-in result.
        val googleSignInResultLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                handleSignInData(data)
            }
        }

        binding.logInButton.setOnClickListener {
            startGoogleSignIn(googleSignInResultLauncher)
        }

        binding.logOutButton.setOnClickListener {
            signOut()
        }

        return binding.root
    }


    private fun getGoogleSignInClient(): GoogleSignInClient {
        /**
         * Configure sign-in to request the user's ID, email address, and basic
         * profile. ID and basic profile are included in DEFAULT_SIGN_IN.
         */
        val gso = GoogleSignInOptions
            .Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()


        /**
         * Build a GoogleSignInClient with the options specified by gso.
         */
        return GoogleSignIn.getClient(requireActivity(), gso);
    }

    private fun startGoogleSignIn(resultLauncher: ActivityResultLauncher<Intent>) {

        if (!isUserSignedIn()) {
            val signInIntent = getGoogleSignInClient().signInIntent
            resultLauncher.launch(signInIntent)
        } else {
            Toast.makeText(requireActivity(), "User already signed in", Toast.LENGTH_SHORT).show()
        }

    }

    private fun isUserSignedIn(): Boolean {

        val account = GoogleSignIn.getLastSignedInAccount(requireActivity())
        return account != null

    }

    private fun signOut() {
        if (isUserSignedIn()) {
            getGoogleSignInClient().signOut().addOnCompleteListener {
                if (it.isSuccessful) {
                    Toast.makeText(requireActivity(), " Signed out ", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireActivity(), " Error ", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }


    private fun handleSignInData(data: Intent?) {
        val getAccountTask = GoogleSignIn.getSignedInAccountFromIntent(data)
        try {
            val account = getAccountTask.getResult(ApiException::class.java)

            // User signed in successfully.
            "account ${account.account}".print()
            "displayName ${account.displayName}".print()
            "Email ${account.email}".print()
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
    }
}


fun Any.print() {
    Log.v(DriveFragment.TAG_KOTLIN, " $this")
}
