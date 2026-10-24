package com.example.myapplication.data

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface MoodifyServerApi {
    @GET("api/search")
    suspend fun searchSongs(@Query("q") query: String): Response<JsonObject>

    @GET("api/tracks/{id}/lyrics")
    suspend fun getLyrics(@Path("id") id: String): Response<JsonObject>

    @GET("api/discovery/explore")
    suspend fun getExplore(@Query("cursor") cursor: String? = null): Response<JsonObject>

    @GET("api/discovery/home")
    suspend fun getHome(@Query("cursor") cursor: String? = null): Response<JsonObject>

    @GET("api/discovery/charts")
    suspend fun getCharts(@Query("cursor") cursor: String? = null): Response<JsonObject>

    @GET("api/discovery/browse/{browseId}")
    suspend fun browse(
        @Path("browseId") browseId: String,
        @Query("params") params: String? = null,
        @Query("cursor") cursor: String? = null
    ): Response<JsonObject>

    @GET("api/discovery/search")
    suspend fun searchDiscovery(
        @Query("q") query: String,
        @Query("type") type: String? = null,
        @Query("cursor") cursor: String? = null
    ): Response<JsonObject>
}
