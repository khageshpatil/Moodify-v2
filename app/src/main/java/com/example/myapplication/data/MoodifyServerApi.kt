package com.example.myapplication.data

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface MoodifyServerApi {
    @GET("api/search")
    suspend fun searchSongs(@Query("q") query: String): Response<JsonObject>
}
