package com.example.trackmate.services

import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class EditProfileRequest(
    val bio: String
)

/** Vehicle ids, as in GET /vehicle. */
@JsonClass(generateAdapter = true)
data class EditVehiclesRequest(
    val vehicles: List<String>
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
        @Part file: MultipartBody.Part,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("user/image")
    @Streaming
    suspend fun getProfileImage(
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @PUT("user")
    suspend fun editProfile(
        @Body data: EditProfileRequest,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("user/vehicles")
    suspend fun getVehicles(
        @Query("lang") lang: String = "en"
    ): Response<List<String>>

    @PUT("user/vehicles")
    suspend fun editVehicles(
        @Body data: EditVehiclesRequest,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @GET("user")
    suspend fun getProfile(
        @Query("id") id: Int,
        @Query("lang") lang: String = "en"
    ): Response<ProfileResponse>
}
