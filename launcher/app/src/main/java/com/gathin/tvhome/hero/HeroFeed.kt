package com.gathin.tvhome.hero

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** The pure half of the feed: ranking, filtering and picking artwork. No Android, no network (unit-tested). */
object HeroFeed {
    const val SLIDES = 10
    /** More candidates than slides, so titles dropped for missing artwork still leave a full carousel. */
    const val CANDIDATES = 14
    /** After a fetch that brought nothing (offline, TMDB down), the next try waits this long, not the next Home. */
    const val RETRY_AFTER_MS = 60L * 60 * 1000

    /**
     * Movies and series alternately, each list in its own order (most watchers first), starting with whichever
     * list's first title has more watchers. When one list runs out the other fills the rest. Duplicates dropped.
     */
    fun interleave(movies: List<Candidate>, shows: List<Candidate>, max: Int = CANDIDATES): List<Candidate> {
        val m = movies.sortedByDescending { it.watchers ?: 0 }
        val s = shows.sortedByDescending { it.watchers ?: 0 }
        var takeMovie = (m.firstOrNull()?.watchers ?: -1) >= (s.firstOrNull()?.watchers ?: -1)
        val out = LinkedHashMap<String, Candidate>()
        var i = 0
        var j = 0
        while (out.size < max && (i < m.size || j < s.size)) {
            val next = when {
                takeMovie && i < m.size -> m[i++]
                !takeMovie && j < s.size -> s[j++]
                i < m.size -> m[i++]
                else -> s[j++]
            }
            out.putIfAbsent("${next.type}:${next.tmdbId}", next)
            takeMovie = !takeMovie
        }
        return out.values.toList()
    }

    fun fromTrakt(movies: List<TraktTrendingMovie>, shows: List<TraktTrendingShow>): List<Candidate> = interleave(
        movies.mapNotNull { t -> t.movie.ids.tmdb?.let { Candidate(it, TYPE_MOVIE, t.watchers) } },
        shows.mapNotNull { t -> t.show.ids.tmdb?.let { Candidate(it, TYPE_TV, t.watchers) } },
    )

    /** TMDB's own trending order, movies and series only, without adult titles or titles with no backdrop. */
    fun fromTmdbTrending(page: TmdbPage, max: Int = CANDIDATES): List<Candidate> = page.results
        .filter { (it.mediaType == TYPE_MOVIE || it.mediaType == TYPE_TV) && !it.adult && !it.backdropPath.isNullOrBlank() }
        .map { Candidate(it.id, it.mediaType!!, null) }
        .distinctBy { "${it.type}:${it.tmdbId}" }
        .take(max)

    /** The slide for a title's details, or null when it is adult, untitled or has no backdrop. */
    fun toSlide(c: Candidate, d: TmdbDetails, language: String): Slide? {
        if (d.adult) return null
        val title = (if (c.type == TYPE_TV) d.name else d.title)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val backdrop = pickBackdrop(d) ?: return null
        val date = if (c.type == TYPE_TV) d.firstAirDate else d.releaseDate
        return Slide(
            tmdbId = d.id,
            type = c.type,
            title = title,
            overview = d.overview?.trim().orEmpty(),
            year = date?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty(),
            rating = d.voteAverage,
            genres = d.genres.map { it.name }.filter { it.isNotBlank() }.take(2),
            backdropPath = backdrop,
            logoPath = pickLogo(d.images?.logos.orEmpty(), language),
            watchers = c.watchers,
            seasons = if (c.type == TYPE_TV) d.numberOfSeasons?.takeIf { it > 0 } else null,
        )
    }

    /**
     * A textless backdrop when TMDB has one (the logo or title is drawn over it, and a backdrop with the title
     * printed in it would show the name twice), the best voted first; otherwise the title's default backdrop.
     */
    fun pickBackdrop(d: TmdbDetails): String? {
        val textless = d.images?.backdrops.orEmpty()
            .filter { it.language == null && it.filePath.isNotBlank() && (it.width == 0 || it.width >= 1280) }
            .maxByOrNull { it.voteAverage }
        return textless?.filePath ?: d.backdropPath?.takeIf { it.isNotBlank() }
    }

    /** A PNG logo in the slide's language, else English, else any; the best voted in that group. SVG is skipped. */
    fun pickLogo(logos: List<TmdbImage>, language: String): String? {
        val usable = logos.filter { it.filePath.endsWith(".png", ignoreCase = true) }
        val lang = language.substringBefore('-').lowercase(Locale.ROOT)
        return (usable.filter { it.language == lang }.takeIf { it.isNotEmpty() }
            ?: usable.filter { it.language == "en" }.takeIf { it.isNotEmpty() }
            ?: usable)
            .maxByOrNull { it.voteAverage }?.filePath
    }

    /**
     * One fetch per calendar day on the TV's clock: the feed is stale once the local date is no longer the date it
     * was fetched on (or the language changed). A clock set backwards also reads as another day.
     */
    fun isStale(feed: Feed?, nowMs: Long, language: String, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        feed == null || feed.language != language || day(feed.fetchedAtMs, zone) != day(nowMs, zone)

    /** Whether a fetch may start now, given when the last one started (null: never). */
    fun mayAttempt(lastAttemptMs: Long?, nowMs: Long): Boolean =
        lastAttemptMs == null || nowMs - lastAttemptMs !in 0 until RETRY_AFTER_MS

    private fun day(ms: Long, zone: ZoneId) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** "12.4K", "980", "1.2M" for the kicker. */
    fun compactCount(n: Int): String = when {
        n >= 1_000_000 -> trim1(n / 1_000_000.0) + "M"
        n >= 1_000 -> trim1(n / 1_000.0) + "K"
        else -> n.toString()
    }

    private fun trim1(v: Double): String {
        val s = String.format(Locale.ROOT, "%.1f", v)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    fun imageUrl(path: String, size: String): String = "https://image.tmdb.org/t/p/$size$path"
}
