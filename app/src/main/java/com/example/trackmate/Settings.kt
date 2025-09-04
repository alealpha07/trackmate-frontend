package com.example.trackmate

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class Settings : Fragment() {
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)
        val btnLogout = view.findViewById<Button>(R.id.btnLogout)

        btnLogout.setOnClickListener {
            apiCallCoroutine.launch {
                try {
                    (activity as MainActivity).authService.logout()
                    (activity as MainActivity).cookieJar.clearCookies()
                    (activity as MainActivity).cache.evictAll()
                    withContext(Dispatchers.Main) {
                        findNavController().navigate(
                            R.id.loginRegister,
                            null,
                            NavOptions.Builder()
                                .setPopUpTo(R.id.settings, true)
                                .build()
                        )
                    }
                }
                catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
        }

        return view
    }
}
