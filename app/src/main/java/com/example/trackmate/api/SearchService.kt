package com.example.trackmate.api

import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

sealed class SearchItem {
    abstract val type: String
    abstract val id: Int
    // friends, posts or km, by the sort
    abstract val score: Double
}

@JsonClass(generateAdapter = true)
data class UserSearchResponse(
    override val type: String,
    override val id: Int,
    val username: String,
    val bio: String,
    val level: Int,
    val experience: Int,
    val friendsCount: Int,
    val postsCount: Int,
    val totalTravelled: Double,
    override val score: Double
) : SearchItem()

@JsonClass(generateAdapter = true)
data class PostSearchResponse(
    override val type: String,
    override val id: Int,
    val title: String,
    val description: String,
    val createdAt: String,
    val trackId: Int,
    val likesCount: Int,
    val savesCount: Int,
    val username: String,
    val userId: Int,
    override val score: Double
) : SearchItem()

interface SearchService {
    @GET("search")
    suspend fun search(
        @Query("text") text: String,
        @Query("sortBy") sortBy: String = "score"
    ): Response<List<SearchItem>>
}
