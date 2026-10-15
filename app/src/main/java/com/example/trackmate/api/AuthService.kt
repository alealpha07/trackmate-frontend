package com.example.trackmate.api

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
data class ChangePasswordRequest(
    val oldPassword: String,
    val newPassword: String
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
        @Body credentials: LoginRequest
    ): Response<ResponseBody>

    @POST("auth/register")
    suspend fun register(
        @Body credentials: RegisterRequest
    ): Response<ResponseBody>

    @POST("auth/logout")
    suspend fun logout(): Response<Unit>

    @GET("auth/user")
    suspend fun getUser(): Response<UserResponse>

    @PUT("auth/password")
    suspend fun changePassword(
        @Body data: ChangePasswordRequest
    ): Response<ResponseBody>
}
