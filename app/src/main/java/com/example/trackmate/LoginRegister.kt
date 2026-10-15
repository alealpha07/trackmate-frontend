package com.example.trackmate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.constraintlayout.widget.Group
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.trackmate.api.AuthService
import com.example.trackmate.api.LoginRequest
import com.example.trackmate.api.RegisterRequest
import com.example.trackmate.api.UserResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import android.util.Log

class LoginRegister : Fragment() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSubmit: Button
    private lateinit var tvToggleMode: TextView
    private lateinit var loginFormGroup: Group
    private lateinit var splashGroup: Group
    private lateinit var api: AuthService
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    private var isRegisterMode = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_login_register, container, false)

        etUsername = view.findViewById(R.id.etUsername)
        etPassword = view.findViewById(R.id.etPassword)
        etConfirmPassword = view.findViewById(R.id.etConfirmPassword)
        btnSubmit = view.findViewById(R.id.btnSubmit)
        tvToggleMode = view.findViewById(R.id.tvToggleMode)
        loginFormGroup = view.findViewById(R.id.loginFormGroup)
        splashGroup = view.findViewById(R.id.splashGroup)
        api = (activity as MainActivity).authService

        tvToggleMode.setOnClickListener {
            isRegisterMode = !isRegisterMode
            updateUIForMode()
        }

        btnSubmit.setOnClickListener {
            val username = etUsername.text.toString().trim()
            val password = etPassword.text.toString().trim()
            val confirmPassword = etConfirmPassword.text.toString().trim()

            if (isRegisterMode) {
                registerUser(username, password, confirmPassword)
            } else {
                loginUser(username, password)
            }
        }

        // check if user is logged in
        apiCallCoroutine.launch {
            try {
                val userResponse: Response<UserResponse> =
                    api.getUser()
                withContext(Dispatchers.Main) {
                    if (userResponse.isSuccessful) {
                        goToHome()
                    } else {
                        showLoginForm()
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
                withContext(Dispatchers.Main) { showLoginForm() }
            }
        }
        return view
    }

    private fun showLoginForm() {
        splashGroup.visibility = View.GONE
        loginFormGroup.visibility = View.VISIBLE
    }

    private fun updateUIForMode() {
        etConfirmPassword.visibility = if (isRegisterMode) View.VISIBLE else View.GONE
        btnSubmit.setText(if (isRegisterMode) R.string.auth_register else R.string.auth_login)
        tvToggleMode.setText(if (isRegisterMode) R.string.auth_switch_to_login else R.string.auth_switch_to_register)
    }

    private fun loginUser(username: String, password: String, justRegistered: Boolean = false) {
        apiCallCoroutine.launch {
            try {
                val response: Response<ResponseBody> = api.login(
                    LoginRequest(username, password)
                )
                if (!response.isSuccessful) {
                    val errorMsg = response.errorBody()?.string() ?: response.message()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        if (justRegistered) {
                            goToProfileEdit()
                        } else {
                            Toast.makeText(
                                requireContext(),
                                response.body()?.string() ?: response.message(), Toast.LENGTH_SHORT
                            ).show()
                            goToHome()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun registerUser(username: String, password: String, confirmPassword: String) {
        apiCallCoroutine.launch {
            try {
                val response: Response<ResponseBody> = api.register(
                    RegisterRequest(username, password, confirmPassword)
                )
                if (!response.isSuccessful) {
                    val errorMsg = response.errorBody()?.string() ?: response.message()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            requireContext(),
                            response.body()?.string() ?: response.message(), Toast.LENGTH_SHORT
                        ).show()
                        // if the login fails the form is already in login mode
                        isRegisterMode = false
                        updateUIForMode()
                        loginUser(username, password, justRegistered = true)
                    }
                }
            }
            catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
    }

    private fun goToHome() {
        findNavController().navigate(
            R.id.home,
            null,
            NavOptions.Builder()
                .setPopUpTo(R.id.loginRegister, true)
                .build()
        )
    }

    // home stays below, so saving or going back lands there
    private fun goToProfileEdit() {
        goToHome()
        findNavController().navigate(R.id.profileEdit)
    }
}
