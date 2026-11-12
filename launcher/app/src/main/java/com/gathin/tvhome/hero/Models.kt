package com.gathin.tvhome.hero

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val TYPE_MOVIE = "movie"
const val TYPE_TV = "tv"
const val SOURCE_TRAKT = "trakt"
const val SOURCE_TMDB = "tmdb"

/** One slide on Home: everything the carousel draws, cached as JSON so Home has it offline. */
@Serializable
data class Slide(
    val tmdbId: Int,
    val type: String,
    val title: String,
    val overview: String = "",
    val year: String = "",
    val rating: Double = 0.0,
    val genres: List<String> = emptyList(),
    val backdropPath: String,
    val logoPath: String? = null,
    /** Trakt's people watching right now; null when the feed came from TMDB. */
    val watchers: Int? = null,
    val seasons: Int? = null,
    /** Minutes: a movie's runtime, a series' episode length; null when TMDB has none. */
    val runtime: Int? = null,
    /** A YouTube video id for the title's trailer; null when TMDB has none (feeds cached before trailers, too). */
    val trailerKey: String? = null,
) {
    val isSeries: Boolean get() = type == TYPE_TV
    val key: String get() = "$type:$tmdbId"
}

@Serializable
data class Feed(
    val fetchedAtMs: Long,
    val language: String,
    val source: String,
    val slides: List<Slide>,
    /** [HeroFeed.FEED_VERSION] when saved; files from before the field read as 1. */
    val version: Int = 1,
)

/** A title to look up on TMDB, in the order it should appear. */
data class Candidate(val tmdbId: Int, val type: String, val watchers: Int?)

internal val heroJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

// ---- TMDB ---------------------------------------------------------------------------------------

@Serializable
data class TmdbPage(val results: List<TmdbTrendingItem> = emptyList())

@Serializable
data class TmdbTrendingItem(
    val id: Int,
    @SerialName("media_type") val mediaType: String? = null,
    val adult: Boolean = false,
    @SerialName("backdrop_path") val backdropPath: String? = null,
)

@Serializable
data class TmdbDetails(
    val id: Int,
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val adult: Boolean = false,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    val runtime: Int? = null,
    @SerialName("episode_run_time") val episodeRunTime: List<Int> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisode: TmdbEpisode? = null,
    val images: TmdbImages? = null,
    val videos: TmdbVideos? = null,
)

@Serializable
data class TmdbEpisode(val runtime: Int? = null)

@Serializable
data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbVideo(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
)

@Serializable
data class TmdbGenre(val name: String = "")

@Serializable
data class TmdbImages(
    val logos: List<TmdbImage> = emptyList(),
    val backdrops: List<TmdbImage> = emptyList(),
)

@Serializable
data class TmdbImage(
    @SerialName("file_path") val filePath: String,
    @SerialName("iso_639_1") val language: String? = null,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    val width: Int = 0,
)

// ---- Trakt --------------------------------------------------------------------------------------

@Serializable
data class TraktIds(val tmdb: Int? = null)

@Serializable
data class TraktItem(val title: String? = null, val year: Int? = null, val ids: TraktIds = TraktIds())

@Serializable
data class TraktTrendingMovie(val watchers: Int = 0, val movie: TraktItem)

@Serializable
data class TraktTrendingShow(val watchers: Int = 0, val show: TraktItem)
