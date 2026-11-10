package com.gathin.tvhome.hero

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.util.concurrent.TimeUnit

/**
 * Every 6 hours while the TV is on and online: fetch the day's feed if Home has not yet (the first run after
 * midnight does, the other runs of the day find it fresh and call no API), and pull its images into the disk
 * cache, so Home after a cold boot (or offline) shows a full carousel at once.
 */
class WarmCacheWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!HeroNet.configured) return Result.success()
        val language = inputData.getString(KEY_LANGUAGE) ?: return Result.success()
        val repo = HeroNet.repository(applicationContext, language)
        repo.refreshIfDue()
        // No retry() on failure: WorkManager would call again within minutes; the next run (or Home) tries later.
        val feed = repo.cached() ?: return Result.success()
        val loader = HeroNet.imageLoader(applicationContext)
        for (s in feed.slides) {
            val urls = listOfNotNull(
                HeroFeed.imageUrl(s.backdropPath, HeroUi.BACKDROP_SIZE),
                s.logoPath?.let { HeroFeed.imageUrl(it, HeroUi.LOGO_SIZE) },
            )
            for (url in urls) {
                loader.execute(
                    ImageRequest.Builder(applicationContext).data(url).memoryCachePolicy(CachePolicy.DISABLED).build()
                )
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "hero-warm-cache"
        private const val KEY_LANGUAGE = "language"

        fun schedule(context: Context, language: String) {
            val request = PeriodicWorkRequestBuilder<WarmCacheWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInputData(workDataOf(KEY_LANGUAGE to language))
                .build()
            // UPDATE keeps the schedule but takes a new language.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
