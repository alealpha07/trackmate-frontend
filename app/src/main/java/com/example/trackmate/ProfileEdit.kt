package com.example.trackmate

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.trackmate.services.AuthService
import com.example.trackmate.services.EditProfileRequest
import com.example.trackmate.services.EditVehiclesRequest
import com.example.trackmate.services.ProfileService
import com.example.trackmate.services.UserResponse
import com.example.trackmate.util.ImageUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response

class ProfileEdit : Fragment() {
    private lateinit var etBio: EditText
    private lateinit var btnSaveProfile: Button
    private lateinit var imgProfile: ImageView
    private lateinit var btnChangeImage: Button
    private lateinit var vehicleChecks: Map<Vehicle, CheckBox>
    private lateinit var authApi: AuthService
    private lateinit var profileApi: ProfileService
    private var selectedImageUri: Uri? = null
    private val apiCallCoroutine = CoroutineScope(Dispatchers.IO)

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                selectedImageUri = it
                imgProfile.setImageURI(it)
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_profile_edit, container, false)

        etBio = view.findViewById(R.id.etBio)
        btnSaveProfile = view.findViewById(R.id.btnSaveProfile)
        imgProfile = view.findViewById(R.id.imgProfile)
        btnChangeImage = view.findViewById(R.id.btnChangeImage)
        profileApi = (activity as MainActivity).profileService
        authApi = (activity as MainActivity).authService
        vehicleChecks = bindVehicleChecks(view.findViewById(R.id.vehicleChecks))

        apiCallCoroutine.launch {
            try {
                val userResponse: Response<UserResponse> = authApi.getUser()
                if (userResponse.isSuccessful && userResponse.body() != null) {
                    val user = userResponse.body()!!
                    val imageResponse = profileApi.getProfileImage(user.id)
                    if (imageResponse.isSuccessful && imageResponse.body() != null) {
                        val bitmap = withContext(Dispatchers.IO) {
                            val inputStream = imageResponse.body()!!.byteStream()
                            BitmapFactory.decodeStream(inputStream)
                        }
                        withContext(Dispatchers.Main) {
                            imgProfile.setImageBitmap(bitmap)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        etBio.setText(user.bio)
                    }
                }
                val vehicles = Vehicle.loadProfile(requireContext().applicationContext, profileApi)
                withContext(Dispatchers.Main) {
                    vehicleChecks.forEach { (vehicle, check) -> check.isChecked = vehicle in vehicles }
                }
            } catch (e: Exception) {
                Log.d("API-ERROR", e.stackTraceToString())
            }
        }

        btnChangeImage.setOnClickListener {
            pickImageLauncher.launch("image/*")
        }

        btnSaveProfile.setOnClickListener {
            val bio = etBio.text.toString().trim()
            val vehicles = vehicleChecks.filterValues { it.isChecked }.keys.toList()
            if (vehicles.isEmpty()) {
                Toast.makeText(requireContext(), R.string.profile_vehicles_missing, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val appContext = requireContext().applicationContext
            apiCallCoroutine.launch {
                try {
                    if (selectedImageUri != null) {
                        val file = ImageUtils.compressImage(requireContext(), selectedImageUri!!)
                        val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

                        val uploadResponse =
                            (activity as MainActivity).profileService.uploadImage(body)

                        if (!uploadResponse.isSuccessful) {
                            withContext(Dispatchers.Main) {
                                val errorMsg =
                                    uploadResponse.errorBody()?.string() ?: "Image upload failed"
                                Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT)
                                    .show()
                            }
                            return@launch
                        }
                    }

                    val bioResponse =
                        (activity as MainActivity).profileService.editProfile(EditProfileRequest(bio))
                    val response = if (bioResponse.isSuccessful) {
                        profileApi.editVehicles(EditVehiclesRequest(vehicles.map { it.id }))
                    } else bioResponse
                    if (response.isSuccessful) Vehicle.saveProfile(appContext, vehicles)
                    withContext(Dispatchers.Main) {
                        if (response.isSuccessful) {
                            Toast.makeText(
                                requireContext(),
                                "Profile updated successfully",
                                Toast.LENGTH_SHORT
                            ).show()
                            findNavController().popBackStack()
                        } else {
                            val errorMsg = response.errorBody()?.string() ?: "Update failed"
                            Toast.makeText(requireContext(), errorMsg, Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.d("API-ERROR", e.stackTraceToString())
                }
            }
        }

        return view
    }

    private fun bindVehicleChecks(container: LinearLayout): Map<Vehicle, CheckBox> {
        val profile = Vehicle.profile(requireContext())
        return Vehicle.entries.associateWith { vehicle ->
            CheckBox(requireContext()).apply {
                setText(vehicle.label)
                setCompoundDrawablesRelativeWithIntrinsicBounds(vehicle.icon, 0, 0, 0)
                compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
                isChecked = vehicle in profile
                container.addView(this)
            }
        }
    }
}
