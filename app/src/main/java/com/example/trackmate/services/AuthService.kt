package com.example.trackmate.services

import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class LoginRequest(
    val username: String,
    val password: String
)

@JsonClass(generateAdapter = true)
data class RegisterRequest(
    val username: String,
    val password: String,
    val confirmPassword: String
)

@JsonClass(generateAdapter = true)
data class UserResponse(
    val id: Int,
    val username: String,
    val bio: String,
    val level: Int,
    val experience: Int
)

interface AuthService {

    @POST("auth/login")
    suspend fun login(
        @Body credentials: LoginRequest,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @POST("auth/register")
    suspend fun register(
        @Body credentials: RegisterRequest,
        @Query("lang") lang: String = "en"
    ): Response<ResponseBody>

    @POST("auth/logout")
    suspend fun logout(
        @Query("lang") lang: String = "en"
    ): Response<Unit>

    @GET("auth/user")
    suspend fun getUser(
        @Query("lang") lang: String = "en"
    ): Response<UserResponse>
}
