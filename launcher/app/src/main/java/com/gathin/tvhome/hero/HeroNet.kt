package com.gathin.tvhome.hero

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.gathin.tvhome.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/** One HTTP client, one image loader and the two APIs, shared by Home and the warm-cache worker. */
object HeroNet {
    private const val USER_AGENT = "tvhome-hero/1.0 (Android TV launcher)"

    val tmdbToken: String get() = BuildConfig.TMDB_TOKEN
    val traktClientId: String get() = BuildConfig.TRAKT_CLIENT_ID
    val configured: Boolean get() = tmdbToken.isNotBlank()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    val tmdb: TmdbApi by lazy {
        val token = tmdbToken
        // A v4 read access token is a JWT ("eyJ…") sent as a bearer; anything else is a v3 api_key.
        val bearer = token.startsWith("eyJ")
        val api = client.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(
                if (bearer) req.newBuilder().header("Authorization", "Bearer $token").build()
                else req.newBuilder().url(req.url.newBuilder().addQueryParameter("api_key", token).build()).build()
            )
        }.build()
        retrofit("https://api.themoviedb.org/3/", api).create(TmdbApi::class.java)
    }

    /** Null without a Trakt client id: the feed then comes from TMDB trending. */
    val trakt: TraktApi? by lazy {
        val id = traktClientId
        if (id.isBlank()) return@lazy null
        val api = client.newBuilder().addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Content-Type", "application/json")
                    .header("trakt-api-version", "2")
                    .header("trakt-api-key", id)
                    .build()
            )
        }.build()
        retrofit("https://api.trakt.tv/", api).create(TraktApi::class.java)
    }

    @Volatile
    private var loader: ImageLoader? = null

    /**
     * Backdrops and logos. A disk cache that ignores the CDN's cache headers keeps the last feed's images
     * for Home offline; RGB_565 halves an opaque backdrop's memory.
     */
    fun imageLoader(context: Context): ImageLoader = loader ?: synchronized(this) {
        loader ?: ImageLoader.Builder(context.applicationContext)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(context.applicationContext).maxSizePercent(0.12).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.applicationContext.cacheDir.resolve("hero_images"))
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .build()
            .also { loader = it }
    }

    fun repository(context: Context, language: String): HeroRepository = HeroRepository(
        tmdb = tmdb,
        trakt = trakt,
        cacheFile = context.applicationContext.filesDir.resolve("hero/feed.json"),
        language = language,
    )

    private fun retrofit(base: String, client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(base)
        .client(client)
        .addConverterFactory(heroJson.asConverterFactory("application/json".toMediaType()))
        .build()
}
