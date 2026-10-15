package com.example.trackmate.api

import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class EditProfileRequest(
    val bio: String
)

@JsonClass(generateAdapter = true)
data class EditVehiclesRequest(
    val vehicles: List<String>
)

@JsonClass(generateAdapter = true)
data class DeleteAccountRequest(
    val password: String
)

@JsonClass(generateAdapter = true)
data class ProfileResponse(
    val username: String,
    val bio: String,
    val level: Int,
    val experience: Int,
    val friendsNumber: Int,
    val totalTravelledLength: Float,
)

interface ProfileService {
    @Multipart
    @POST("user/image")
    suspend fun uploadImage(
        @Part file: MultipartBody.Part
    ): Response<ResponseBody>

    @GET("user/image")
    @Streaming
    suspend fun getProfileImage(
        @Query("id") id: Int
    ): Response<ResponseBody>

    @DELETE("user/image")
    suspend fun deleteImage(): Response<ResponseBody>

    // personal data, keep it out of the HTTP cache
    @Headers("Cache-Control: no-store")
    @GET("user/export")
    @Streaming
    suspend fun exportData(): Response<ResponseBody>

    @HTTP(method = "DELETE", path = "user", hasBody = true)
    suspend fun deleteAccount(
        @Body data: DeleteAccountRequest
    ): Response<ResponseBody>

    @PUT("user")
    suspend fun editProfile(
        @Body data: EditProfileRequest
    ): Response<ResponseBody>

    @GET("user/vehicles")
    suspend fun getVehicles(): Response<List<String>>

    @PUT("user/vehicles")
    suspend fun editVehicles(
        @Body data: EditVehiclesRequest
    ): Response<ResponseBody>

    @GET("user")
    suspend fun getProfile(
        @Query("id") id: Int
    ): Response<ProfileResponse>
}
