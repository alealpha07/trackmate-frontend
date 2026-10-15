package com.example.trackmate.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.*

@JsonClass(generateAdapter = true)
data class PostRequest(
    val id: Int,
    val title: String,
    val description: String
)

@JsonClass(generateAdapter = true)
data class PostLikeSaveRequest(
    val id: Int
)

@JsonClass(generateAdapter = true)
data class PostId(
    val id: Int
)

@JsonClass(generateAdapter = true)
data class PostItem(
    val id: Int,
    val trackId: Int,
    val imageCount: Int,
    val title: String,
    val description: String,
    val saved: Boolean,
    val liked: Boolean,
    val likeCount: Int,
    val username: String,
    val userId: Int
)

interface PostService {
    @Multipart
    @POST("post/image")
    suspend fun uploadPostImage(
        @Part file: MultipartBody.Part,
        @Query("id") id: Int
    ): Response<ResponseBody>

    @GET("post/image")
    @Streaming
    suspend fun getPostImage(
        @Query("id") id: Int,
        @Query("index") index: Int
    ): Response<ResponseBody>

    @POST("post")
    suspend fun createPost(
        @Body data: PostRequest
    ): Response<PostId>

    @PUT("post")
    suspend fun editPost(
        @Body data: PostRequest
    ): Response<ResponseBody>

    @DELETE("post")
    suspend fun deletePost(
        @Query("id") id: Int
    ): Response<ResponseBody>

    @GET("post")
    suspend fun getPost(
        @Query("id") id: Int
    ): Response<PostItem>

    @POST("post/like")
    suspend fun likePost(
        @Body data: PostLikeSaveRequest
    ): Response<ResponseBody>

    @DELETE("post/like")
    suspend fun unLikePost(
        @Query("id") id: Int
    ): Response<ResponseBody>

    @POST("post/save")
    suspend fun savePost(
        @Body data: PostLikeSaveRequest
    ): Response<ResponseBody>

    @DELETE("post/save")
    suspend fun unSavePost(
        @Query("id") id: Int
    ): Response<ResponseBody>

    @GET("post/user")
    suspend fun getUserPosts(
        @Query("id") id: Int
    ): Response<List<PostItem>>

    @GET("post/friends")
    suspend fun getFriendsPosts(
        @Query("offset") offset: Int
    ): Response<List<PostItem>>

    @GET("post/saved")
    suspend fun getSavedPosts(): Response<List<PostItem>>

    @GET("post/trending")
    suspend fun getTrendingPosts(
        @Query("offset") offset: Int
    ): Response<List<PostItem>>

}
