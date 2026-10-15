package com.example.trackmate.api

import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

fun buildMoshi(): Moshi = Moshi.Builder()
    .add(
        PolymorphicJsonAdapterFactory.of(SearchItem::class.java, "type")
            .withSubtype(UserSearchResponse::class.java, "user")
            .withSubtype(PostSearchResponse::class.java, "post")
    )
    .add(KotlinJsonAdapterFactory())
    .build()

fun buildRetrofit(baseUrl: String, client: OkHttpClient, moshi: Moshi = buildMoshi()): Retrofit = Retrofit.Builder()
    .baseUrl(baseUrl)
    .client(client)
    .addConverterFactory(MoshiConverterFactory.create(moshi))
    .build()
