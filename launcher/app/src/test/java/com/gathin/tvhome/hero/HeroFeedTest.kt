package com.gathin.tvhome.hero

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class HeroFeedTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun m(id: Int, w: Int?) = Candidate(id, TYPE_MOVIE, w)
    private fun s(id: Int, w: Int?) = Candidate(id, TYPE_TV, w)

    @Test
    fun interleaveStartsWithTheMostWatchedAndAlternates() {
        val out = HeroFeed.interleave(listOf(m(1, 50), m(2, 40)), listOf(s(10, 90), s(11, 10), s(12, 5)), max = 10)
        assertEquals(listOf("tv:10", "movie:1", "tv:11", "movie:2", "tv:12"), out.map { "${it.type}:${it.tmdbId}" })
    }

    @Test
    fun interleaveCapsAndDropsDuplicates() {
        val out = HeroFeed.interleave(listOf(m(1, 9), m(1, 8), m(2, 7)), listOf(s(1, 1)), max = 3)
        // The same TMDB id as a movie and as a series are different titles; the repeated movie is dropped.
        assertEquals(listOf("movie:1", "tv:1", "movie:2"), out.map { "${it.type}:${it.tmdbId}" })
    }

    @Test
    fun traktRowsWithoutATmdbIdAreSkipped() {
        val out = HeroFeed.fromTrakt(
            listOf(TraktTrendingMovie(100, TraktItem("A", 2024, TraktIds(5))), TraktTrendingMovie(90, TraktItem("B", 2024, TraktIds(null)))),
            listOf(TraktTrendingShow(80, TraktItem("C", 2023, TraktIds(7)))),
        )
        assertEquals(listOf(5, 7), out.map { it.tmdbId })
        assertEquals(100, out[0].watchers)
    }

    @Test
    fun tmdbTrendingKeepsOnlyMoviesAndSeriesWithABackdrop() {
        val page = heroJson.decodeFromString(
            TmdbPage.serializer(),
            """{"results":[
                {"id":1,"media_type":"movie","backdrop_path":"/a.jpg"},
                {"id":2,"media_type":"person"},
                {"id":3,"media_type":"tv","backdrop_path":null},
                {"id":4,"media_type":"tv","backdrop_path":"/d.jpg","adult":true},
                {"id":5,"media_type":"tv","backdrop_path":"/e.jpg","unknown_field":42}
            ]}""",
        )
        assertEquals(listOf(1, 5), HeroFeed.fromTmdbTrending(page).map { it.tmdbId })
    }

    @Test
    fun slideUsesSeriesFieldsAndATextlessBackdrop() {
        val d = TmdbDetails(
            id = 7, name = "Severance", title = null, overview = " Work. ", firstAirDate = "2022-02-18",
            voteAverage = 8.4, genres = listOf(TmdbGenre("Drama"), TmdbGenre("Mystery"), TmdbGenre("Sci-Fi")),
            backdropPath = "/with-title.jpg", numberOfSeasons = 2,
            images = TmdbImages(
                logos = listOf(TmdbImage("/logo.svg", "en", 9.0), TmdbImage("/logo-en.png", "en", 5.0), TmdbImage("/logo-tr.png", "tr", 1.0)),
                backdrops = listOf(TmdbImage("/en.jpg", "en", 9.0, 1920), TmdbImage("/clean.jpg", null, 5.0, 3840)),
            ),
        )
        val slide = HeroFeed.toSlide(Candidate(7, TYPE_TV, 1234), d, "en-US")!!
        assertEquals("Severance", slide.title)
        assertEquals("Work.", slide.overview)
        assertEquals("2022", slide.year)
        assertEquals(listOf("Drama", "Mystery"), slide.genres)
        assertEquals("/clean.jpg", slide.backdropPath)
        assertEquals("/logo-en.png", slide.logoPath)   // the SVG is skipped
        assertEquals(2, slide.seasons)
        assertEquals(1234, slide.watchers)
        assertEquals("/logo-tr.png", HeroFeed.pickLogo(d.images!!.logos, "tr-TR"))
    }

    @Test
    fun adultUntitledOrBackdropLessTitlesAreDropped() {
        val c = Candidate(1, TYPE_MOVIE, null)
        assertNull(HeroFeed.toSlide(c, TmdbDetails(id = 1, title = "X", backdropPath = "/b.jpg", adult = true), "en"))
        assertNull(HeroFeed.toSlide(c, TmdbDetails(id = 1, title = " ", backdropPath = "/b.jpg"), "en"))
        assertNull(HeroFeed.toSlide(c, TmdbDetails(id = 1, title = "X"), "en"))
        // A movie reads title, not name, and never carries seasons.
        val ok = HeroFeed.toSlide(c, TmdbDetails(id = 1, title = "X", name = "Y", backdropPath = "/b.jpg", numberOfSeasons = 3), "en")!!
        assertEquals("X", ok.title)
        assertNull(ok.seasons)
    }

    @Test
    fun staleOnlyOnAnotherDay() {
        val utc = ZoneOffset.UTC
        val morning = Instant.parse("2026-11-10T06:00:00Z").toEpochMilli()
        val feed = Feed(fetchedAtMs = morning, language = "en-US", source = SOURCE_TRAKT, slides = emptyList())
        assertFalse(HeroFeed.isStale(feed, Instant.parse("2026-11-10T23:59:59Z").toEpochMilli(), "en-US", utc))
        assertTrue(HeroFeed.isStale(feed, Instant.parse("2026-11-11T00:00:00Z").toEpochMilli(), "en-US", utc))
        assertTrue(HeroFeed.isStale(feed, Instant.parse("2026-11-09T23:00:00Z").toEpochMilli(), "en-US", utc)) // clock back
        assertTrue(HeroFeed.isStale(feed, morning + 1, "tr-TR", utc))                                           // language
        assertTrue(HeroFeed.isStale(null, morning, "en-US", utc))
        // The day is the TV's: 23:00 UTC on the 10th is already the 11th in Istanbul.
        assertTrue(HeroFeed.isStale(feed, Instant.parse("2026-11-10T23:00:00Z").toEpochMilli(), "en-US", ZoneId.of("Europe/Istanbul")))
    }

    @Test
    fun retryGate() {
        assertTrue(HeroFeed.mayAttempt(null, 0))
        assertFalse(HeroFeed.mayAttempt(1_000, 1_000 + HeroFeed.RETRY_AFTER_MS - 1))
        assertTrue(HeroFeed.mayAttempt(1_000, 1_000 + HeroFeed.RETRY_AFTER_MS))
        assertTrue(HeroFeed.mayAttempt(5_000, 1_000))   // clock set back
    }

    @Test
    fun compactCounts() {
        assertEquals("980", HeroFeed.compactCount(980))
        assertEquals("12.4K", HeroFeed.compactCount(12_431))
        assertEquals("3K", HeroFeed.compactCount(3_020))
        assertEquals("1.2M", HeroFeed.compactCount(1_234_567))
    }

    // ---- repository, with fake APIs ---------------------------------------------------------------

    private class FakeTmdb(var failIds: Set<Int> = emptySet()) : TmdbApi {
        var trendingCalls = 0
        var detailCalls = 0
        override suspend fun trending(window: String, language: String): TmdbPage {
            trendingCalls++
            return TmdbPage(listOf(TmdbTrendingItem(21, TYPE_MOVIE, backdropPath = "/t.jpg")))
        }

        override suspend fun details(type: String, id: Int, language: String, append: String, imageLanguages: String): TmdbDetails {
            detailCalls++
            if (id in failIds) throw RuntimeException("404")
            return TmdbDetails(id = id, title = "M$id", name = "S$id", backdropPath = "/$id.jpg")
        }
    }

    private class FakeTrakt(val fail: Boolean = false) : TraktApi {
        override suspend fun movies(limit: Int): List<TraktTrendingMovie> {
            if (fail) throw RuntimeException("503")
            return listOf(TraktTrendingMovie(30, TraktItem("M1", ids = TraktIds(1))), TraktTrendingMovie(20, TraktItem("M2", ids = TraktIds(2))))
        }

        override suspend fun shows(limit: Int): List<TraktTrendingShow> =
            listOf(TraktTrendingShow(40, TraktItem("S3", ids = TraktIds(3))))
    }

    @Test
    fun repositoryRanksByTraktAndCachesTheFeed() = runTest {
        val file = tmp.root.resolve("hero/feed.json")
        val tmdb = FakeTmdb(failIds = setOf(2))
        val repo = HeroRepository(tmdb, FakeTrakt(), file, "en-US", clock = { 5_000 }, log = {})
        val feed = repo.refresh()!!
        assertEquals(SOURCE_TRAKT, feed.source)
        assertEquals(listOf("tv:3", "movie:1"), feed.slides.map { it.key })   // 2 failed and is dropped
        assertEquals(listOf("S3", "M1"), feed.slides.map { it.title })
        assertEquals(0, tmdb.trendingCalls)
        assertEquals(feed, repo.cached())
    }

    @Test
    fun onceADayThenOverwrittenTheNextDay() = runTest {
        var now = Instant.parse("2026-11-10T08:00:00Z").toEpochMilli()
        val tmdb = FakeTmdb()
        val repo = HeroRepository(tmdb, FakeTrakt(), tmp.root.resolve("feed.json"), "en-US", clock = { now }, log = {},
            zone = { ZoneOffset.UTC })
        val first = repo.refreshIfDue()!!
        val calls = tmdb.detailCalls
        // Every other visit to Home that day: no API call, the cache stands.
        now += 14 * 60 * 60 * 1000L
        assertNull(repo.refreshIfDue())
        assertEquals(calls, tmdb.detailCalls)
        assertEquals(first, repo.cached())
        // The next day: one fetch, written over yesterday's feed.
        now = Instant.parse("2026-11-11T07:30:00Z").toEpochMilli()
        val second = repo.refreshIfDue()!!
        assertTrue(tmdb.detailCalls > calls)
        assertEquals(now, second.fetchedAtMs)
        assertEquals(second, repo.cached())
    }

    @Test
    fun aFailedFetchWaitsAnHourBeforeTheNext() = runTest {
        var now = Instant.parse("2026-11-10T08:00:00Z").toEpochMilli()
        val tmdb = FakeTmdb(failIds = setOf(1, 2, 3))
        val repo = HeroRepository(tmdb, FakeTrakt(), tmp.root.resolve("feed.json"), "en-US", clock = { now }, log = {},
            zone = { ZoneOffset.UTC })
        assertNull(repo.refreshIfDue())
        val calls = tmdb.detailCalls
        now += 10 * 60 * 1000L
        assertNull(repo.refreshIfDue())                // ten minutes later: not asked again
        assertEquals(calls, tmdb.detailCalls)
        tmdb.failIds = emptySet()
        now += HeroFeed.RETRY_AFTER_MS
        assertEquals(2, repo.refreshIfDue()!!.slides.size.coerceAtMost(2))
    }

    @Test
    fun repositoryFallsBackToTmdbWhenTraktFailsOrHasNoKey() = runTest {
        for (trakt in listOf(FakeTrakt(fail = true), null)) {
            val repo = HeroRepository(FakeTmdb(), trakt, tmp.newFolder().resolve("feed.json"), "en-US", log = {})
            val feed = repo.refresh()!!
            assertEquals(SOURCE_TMDB, feed.source)
            assertEquals(listOf("movie:21"), feed.slides.map { it.key })
        }
    }

    @Test
    fun aFailedRefreshKeepsTheLastGoodFeed() = runTest {
        val file = tmp.root.resolve("feed.json")
        HeroRepository(FakeTmdb(), FakeTrakt(), file, "en-US", log = {}).refresh()
        val broken = HeroRepository(FakeTmdb(failIds = setOf(1, 2, 3, 21)), FakeTrakt(), file, "en-US", log = {})
        assertNull(broken.refresh())
        assertEquals(3, broken.cached()!!.slides.size)   // the first, good feed
    }

    @Test
    fun aCorruptCacheReadsAsNothing() {
        val file = tmp.root.resolve("feed.json").apply { writeText("{not json") }
        assertNull(HeroRepository(FakeTmdb(), null, file, "en-US", log = {}).cached())
    }
}
