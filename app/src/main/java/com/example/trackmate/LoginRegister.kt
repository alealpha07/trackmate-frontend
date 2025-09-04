package com.example.trackmate

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.trackmate.services.LoginRequest
import com.example.trackmate.services.RegisterRequest
import com.example.trackmate.services.UserResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import android.util.Log
import com.example.trackmate.services.AuthService

class LoginRegister : Fragment() {

    private lateinit var etUsername: EditText
    private lateinit var etPassword: EditText
    private lateinit var etConfirmPassword: EditText
    private lateinit var btnSubmit: Button
    private lateinit var tvToggleMode: TextView
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
                if (userResponse.isSuccessful) {
                    withContext(Dispatchers.Main) {
                        goToHome()
                    }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }
        return view
    }

    private fun updateUIForMode() {
        etConfirmPassword.visibility = if (isRegisterMode) View.VISIBLE else View.GONE
        btnSubmit.text = if (isRegisterMode) "Register" else "Login"
        tvToggleMode.text = if (isRegisterMode) {
            "Already have an account? Login here"
        } else {
            "No account? Register here"
        }
    }

    private fun loginUser(username: String, password: String) {
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
                        Toast.makeText(
                            requireContext(),
                            response.body()?.string() ?: response.message(), Toast.LENGTH_SHORT
                        ).show()
                        goToHome()
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
                        isRegisterMode = !isRegisterMode
                        updateUIForMode()
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
}
