package com.gathin.tvhome.hero

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** TMDB v3 (https://developer.themoviedb.org/reference). Auth is added by [HeroNet]. */
interface TmdbApi {
    @GET("trending/all/{window}")
    suspend fun trending(
        @Path("window") window: String,
        @Query("language") language: String,
    ): TmdbPage

    /** Details plus logos, backdrops and videos (for the trailer) in one call. */
    @GET("{type}/{id}")
    suspend fun details(
        @Path("type") type: String,
        @Path("id") id: Int,
        @Query("language") language: String,
        @Query("append_to_response") append: String = "images,videos",
        @Query("include_image_language") imageLanguages: String,
        @Query("include_video_language") videoLanguages: String,
    ): TmdbDetails
}

/** Trakt (https://trakt.docs.apiary.io): the titles with the most people watching right now. */
interface TraktApi {
    @GET("movies/trending")
    suspend fun movies(@Query("limit") limit: Int): List<TraktTrendingMovie>

    @GET("shows/trending")
    suspend fun shows(@Query("limit") limit: Int): List<TraktTrendingShow>
}
