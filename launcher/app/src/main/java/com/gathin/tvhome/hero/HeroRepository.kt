package com.gathin.tvhome.hero

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/**
 * Trakt's ranking (people watching now) dressed with TMDB's artwork and text; TMDB trending when Trakt has no key
 * or fails. The last good feed is kept in [cacheFile] so Home shows it offline.
 *
 * TMDB and Trakt are asked once per calendar day ([refreshIfDue]); the rest of the day Home reads the cache.
 */
class HeroRepository(
    private val tmdb: TmdbApi,
    private val trakt: TraktApi?,
    private val cacheFile: File,
    private val language: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.w(TAG, it) },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val attemptFile = File(cacheFile.path + ".attempt")

    fun cached(): Feed? = try {
        if (cacheFile.isFile) heroJson.decodeFromString(Feed.serializer(), cacheFile.readText()) else null
    } catch (e: Exception) {
        null
    }

    fun isStale(feed: Feed? = cached()): Boolean = HeroFeed.isStale(feed, clock(), language, zone())

    /**
     * Today's feed from the network when the cached one is from another day; null when the cache is still today's,
     * when a fetch failed less than an hour ago, or when this fetch failed (the old feed then stays on screen).
     * Home and the warm-cache worker share one lock, so they never fetch twice at once.
     */
    suspend fun refreshIfDue(): Feed? = LOCK.withLock {
        withContext(Dispatchers.IO) {
            val now = clock()
            val feed = cached()
            if (!HeroFeed.isStale(feed, now, language, zone())) return@withContext null
            if (!HeroFeed.mayAttempt(lastAttempt(), now, feed?.fetchedAtMs)) return@withContext null
            markAttempt(now)
            refresh()
        }
    }

    private fun lastAttempt(): Long? = try {
        attemptFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()
    } catch (e: Exception) {
        null
    }

    private fun markAttempt(now: Long) {
        try {
            attemptFile.parentFile?.mkdirs()
            attemptFile.writeText(now.toString())
        } catch (e: Exception) {
            log("could not note the fetch time: $e")
        }
    }

    /** A fresh feed, saved over the old one; null (and the cache untouched) when nothing usable came back. */
    internal suspend fun refresh(): Feed? = withContext(Dispatchers.IO) {
        val fromTrakt = trakt?.let { api -> attempt("trakt") { traktCandidates(api) } }.orEmpty()
        val source = if (fromTrakt.isNotEmpty()) SOURCE_TRAKT else SOURCE_TMDB
        val candidates = fromTrakt.ifEmpty {
            attempt("tmdb trending") { HeroFeed.fromTmdbTrending(tmdb.trending("week", language)) }.orEmpty()
        }
        val slides = details(candidates)
        if (slides.isEmpty()) return@withContext null
        val feed = Feed(clock(), language, source, slides, HeroFeed.FEED_VERSION)
        save(feed)
        feed
    }

    private suspend fun traktCandidates(api: TraktApi): List<Candidate> = coroutineScope {
        val movies = async { api.movies(HeroFeed.CANDIDATES) }
        val shows = async { api.shows(HeroFeed.CANDIDATES) }
        HeroFeed.fromTrakt(movies.await(), shows.await())
    }

    /**
     * Up to four lookups at a time, in the candidates' order; titles that fail or have no backdrop are dropped. All
     * are kept, not only the first [HeroFeed.SLIDES]: the rest stand in for titles marked as watched.
     */
    private suspend fun details(candidates: List<Candidate>): List<Slide> = coroutineScope {
        val gate = Semaphore(4)
        val imageLanguages = "${language.substringBefore('-')},en,null"
        val videoLanguages = "${language.substringBefore('-')},en"
        candidates.map { c ->
            async {
                gate.withPermit {
                    attempt("tmdb ${c.type} ${c.tmdbId}") {
                        HeroFeed.toSlide(c, tmdb.details(c.type, c.tmdbId, language, imageLanguages = imageLanguages, videoLanguages = videoLanguages), language)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    private fun save(feed: Feed) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(heroJson.encodeToString(Feed.serializer(), feed))
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.delete()
                tmp.renameTo(cacheFile)
            }
        } catch (e: Exception) {
            log("could not save the feed: $e")
        }
    }

    private suspend fun <T> attempt(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log("$what failed: $e")
        null
    }

    private companion object {
        const val TAG = "HeroRepository"
        val LOCK = Mutex()
    }
}
