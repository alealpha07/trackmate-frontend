package com.example.trackmate.profile

import android.app.AlertDialog
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.trackmate.BuildConfig
import com.example.trackmate.MainActivity
import com.example.trackmate.R
import com.example.trackmate.api.ChangePasswordRequest
import com.example.trackmate.api.DeleteAccountRequest
import com.example.trackmate.util.AppLanguage
import com.example.trackmate.util.ThemeMode
import com.example.trackmate.util.chooseLanguage
import com.example.trackmate.util.chosenLanguage
import com.example.trackmate.util.currentLanguage
import com.example.trackmate.util.openLegalPage
import com.example.trackmate.util.openUrl
import com.example.trackmate.util.saveTheme
import com.example.trackmate.util.savedTheme
import com.example.trackmate.util.showAboveKeyboard
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PASSWORD_LENGTH = 8

class Settings : Fragment() {
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri?.let { saveExport(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        view.findViewById<Button>(R.id.btnCredits).setOnClickListener {
            findNavController().navigate(R.id.credits)
        }
        view.findViewById<Button>(R.id.btnPrivacy).setOnClickListener { openLegalPage("privacy") }
        view.findViewById<Button>(R.id.btnTerms).setOnClickListener { openLegalPage("terms") }
        view.findViewById<Button>(R.id.btnTheme).apply {
            text = getString(R.string.settings_value, getString(R.string.settings_theme), getString(savedTheme(context).label))
            setOnClickListener { showThemeDialog() }
        }
        view.findViewById<Button>(R.id.btnLanguage).apply {
            val language = chosenLanguage()?.displayName ?: getString(R.string.settings_system_default)
            text = getString(R.string.settings_value, getString(R.string.settings_language), language)
            setOnClickListener { showLanguageDialog() }
        }
        view.findViewById<Button>(R.id.btnChangePassword).setOnClickListener { showChangePasswordDialog() }
        view.findViewById<Button>(R.id.btnExport).setOnClickListener { exportLauncher.launch("trackmate-data.zip") }
        view.findViewById<Button>(R.id.btnDeleteAccount).setOnClickListener { showDeleteAccountDialog() }

        view.findViewById<Button>(R.id.btnLogout).setOnClickListener {
            val activity = activity as MainActivity
            apiCallCoroutine.launch {
                try {
                    activity.authService.logout()
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
                activity.clearLocalData()
                withContext(Dispatchers.Main) { goToLogin() }
            }
        }

        return view
    }

    private fun goToLogin() {
        if (!isAdded) return
        findNavController().navigate(
            R.id.loginRegister,
            null,
            NavOptions.Builder()
                // nothing of the old session stays under the login screen
                .setPopUpTo(R.id.mobile_navigation, true)
                .build()
        )
    }

    // both recreate the activity, which lands back here
    private fun showThemeDialog() {
        val themes = ThemeMode.entries
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_theme)
            .setSingleChoiceItems(
                themes.map { getString(it.label) }.toTypedArray(),
                themes.indexOf(savedTheme(requireContext()))
            ) { dialog, which ->
                dialog.dismiss()
                saveTheme(requireContext(), themes[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showLanguageDialog() {
        val languages = listOf(null) + AppLanguage.entries
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(
                languages.map { it?.displayName ?: getString(R.string.settings_system_default) }.toTypedArray(),
                languages.indexOf(chosenLanguage())
            ) { dialog, which ->
                dialog.dismiss()
                chooseLanguage(languages[which])
            }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun showChangePasswordDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_password, null)
        val oldLayout = dialogView.findViewById<TextInputLayout>(R.id.oldPasswordLayout)
        val newLayout = dialogView.findViewById<TextInputLayout>(R.id.newPasswordLayout)
        val confirmLayout = dialogView.findViewById<TextInputLayout>(R.id.confirmPasswordLayout)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_change_password)
            .setView(dialogView)
            // set below so a click doesn't close the dialog
            .setPositiveButton(R.string.dialog_save, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .showAboveKeyboard()
        val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val genericError = getString(R.string.error_generic)
        val changedText = getString(R.string.password_changed)
        val api = (activity as MainActivity).authService

        saveButton.setOnClickListener {
            val oldPassword = oldLayout.editText?.text.toString()
            val newPassword = newLayout.editText?.text.toString()
            oldLayout.error = null
            newLayout.error = if (newPassword.length < MIN_PASSWORD_LENGTH) getString(R.string.password_too_short) else null
            confirmLayout.error =
                if (newPassword != confirmLayout.editText?.text.toString()) getString(R.string.password_mismatch) else null
            if (newLayout.error != null || confirmLayout.error != null) return@setOnClickListener

            saveButton.isEnabled = false
            apiCallCoroutine.launch {
                val (status, error) = try {
                    val response = api.changePassword(ChangePasswordRequest(oldPassword, newPassword))
                    response.code() to if (response.isSuccessful) null
                        else response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: genericError
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                    0 to genericError
                }
                withContext(Dispatchers.Main) {
                    saveButton.isEnabled = true
                    when {
                        error == null -> {
                            dialog.dismiss()
                            Toast.makeText(dialog.context, changedText, Toast.LENGTH_LONG).show()
                        }
                        // 403 is a wrong current password, anything else is about the new one
                        status == 403 -> oldLayout.error = error
                        else -> newLayout.error = error
                    }
                }
            }
        }
    }

    private fun saveExport(uri: Uri) {
        val appContext = requireContext().applicationContext
        val api = (activity as MainActivity).profileService
        apiCallCoroutine.launch {
            val saved = try {
                val response = api.exportData()
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    appContext.contentResolver.openOutputStream(uri)?.use { out ->
                        body.byteStream().use { it.copyTo(out) }
                    } != null
                } else false
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
                false
            }
            if (!saved) runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, uri) }
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    appContext,
                    if (saved) R.string.export_saved else R.string.export_failed,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun showDeleteAccountDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_password, null)
        val passwordLayout = dialogView.findViewById<TextInputLayout>(R.id.passwordLayout)
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(R.string.settings_delete_account)
            .setMessage(R.string.delete_account_message)
            .setView(dialogView)
            .setPositiveButton(R.string.delete_account_confirm, null)
            .setNegativeButton(R.string.dialog_cancel, null)
            .showAboveKeyboard()
        val deleteButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val genericError = getString(R.string.error_generic)
        val activity = activity as MainActivity

        deleteButton.setOnClickListener {
            val password = passwordLayout.editText?.text.toString()
            if (password.isEmpty()) return@setOnClickListener
            deleteButton.isEnabled = false
            apiCallCoroutine.launch {
                val error = try {
                    val response = activity.profileService.deleteAccount(DeleteAccountRequest(password))
                    if (response.isSuccessful) null
                    else response.errorBody()?.string()?.takeIf { it.isNotBlank() } ?: genericError
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                    genericError
                }
                if (error == null) activity.clearLocalData()
                withContext(Dispatchers.Main) {
                    if (error == null) {
                        dialog.dismiss()
                        Toast.makeText(activity, R.string.account_deleted, Toast.LENGTH_LONG).show()
                        goToLogin()
                    } else {
                        deleteButton.isEnabled = true
                        passwordLayout.error = error
                    }
                }
            }
        }
    }
}

class Credits : Fragment(R.layout.fragment_credits)

// the privacy notice or the terms, loaded from the server so the texts exist only there
class LegalPage : Fragment(R.layout.fragment_legal) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val page = requireArguments().getString("page") ?: "privacy"
        showTitle(page)

        val webView = view.findViewById<WebView>(R.id.webView)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val error = view.findViewById<TextView>(R.id.tvError)
        val back = requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, false) { webView.goBack() }

        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val path = uri.path?.removePrefix("/")
                if (uri.host == SERVER.host && (path == "privacy" || path == "terms")) {
                    view.loadUrl(legalUrl(path))
                } else {
                    requireContext().openUrl(uri.toString())
                }
                return true
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                back.isEnabled = view.canGoBack()
                url?.toUri()?.path?.removePrefix("/")?.let(::showTitle)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                if (!request.isForMainFrame) return
                webView.visibility = View.GONE
                error.visibility = View.VISIBLE
            }
        }
        webView.loadUrl(legalUrl(page))
    }

    // the pages link to each other, so the title follows the one shown
    private fun showTitle(page: String) {
        if (!isAdded) return
        (activity as AppCompatActivity).supportActionBar?.title =
            getString(if (page == "terms") R.string.legal_terms else R.string.legal_privacy)
    }

    private companion object {
        val SERVER: Uri = BuildConfig.BASE_URL.toUri()

        // app=1 leaves out the web header and footer
        fun legalUrl(page: String) = "${BuildConfig.BASE_URL}/$page?lang=${currentLanguage().tag}&app=1"
    }
}
