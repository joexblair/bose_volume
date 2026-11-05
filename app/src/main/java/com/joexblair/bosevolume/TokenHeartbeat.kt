package com.joexblair.bosevolume

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.joexblair.bosevolume.core.HttpException
import com.joexblair.bosevolume.core.NotSignedInException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Keeps the Bose control token fresh in the background.
 *
 * The speaker rejects commands without a valid control token, and getting a new one takes two
 * internet round-trips (Bose sign-in refresh + token exchange). Without this, the first tap after
 * a quiet spell pays for both. Every ~15 minutes this renews the token if it has less than
 * [MARGIN_SECONDS] left, so a tap almost always finds a valid one and goes straight to the speaker.
 * It also keeps the Bose sign-in itself active.
 */
class TokenHeartbeatWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val auth = Bose.auth(applicationContext)
        if (!auth.isSignedIn) return Result.success()
        return try {
            auth.controlToken(minValidSeconds = MARGIN_SECONDS)
            Result.success()
        } catch (e: NotSignedInException) {
            VolumeWidgetProvider.requestRefresh(applicationContext) // widget shows "Sign in"
            Result.success()
        } catch (e: IOException) {
            Result.retry()
        } catch (e: HttpException) {
            if (e.code >= 500) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val NAME = "bose-token-heartbeat"
        /** Longer than the 15-minute period plus flex, so the token never lapses between runs. */
        const val MARGIN_SECONDS = 25L * 60

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<TokenHeartbeatWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }

        /** Schedule while signed in, cancel otherwise. Safe to call often. */
        fun sync(context: Context) {
            if (Bose.auth(context).isSignedIn) schedule(context) else cancel(context)
        }
    }
}
