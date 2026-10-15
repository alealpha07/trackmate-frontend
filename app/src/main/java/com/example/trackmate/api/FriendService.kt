package com.example.trackmate.api

import com.squareup.moshi.JsonClass
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class FriendRequestAction(
    val id: Int
)

@JsonClass(generateAdapter = true)
data class FriendResponse(
    val id: Int,
    val username: String,
    val email: String? = null,
    val level: Int? = null
)

@JsonClass(generateAdapter = true)
data class FriendRequestsResponse(
    val status: Boolean,
    val senderId: Int,
    val receiverId: Int,
    // received requests carry the sender, sent ones the receiver
    val sender: FriendResponse? = null,
    val receiver: FriendResponse? = null
)

interface FriendService {

    @POST("friend/request")
    suspend fun sendRequest(
        @Body data: FriendRequestAction
    ): Response<ResponseBody>

    @PUT("friend/request")
    suspend fun acceptRequest(
        @Body data: FriendRequestAction
    ): Response<ResponseBody>

    @DELETE("friend")
    suspend fun removeFriend(
        @Query("id") id: Int
    ): Response<ResponseBody>

    @GET("friend/request")
    suspend fun getFriendRequests(): Response<List<FriendRequestsResponse>>

    @GET("friend/request/sent")
    suspend fun getSentFriendRequests(): Response<List<FriendRequestsResponse>>

    @GET("friend")
    suspend fun getFriends(
        @Query("id") id: Int
    ): Response<List<FriendResponse>>
}
