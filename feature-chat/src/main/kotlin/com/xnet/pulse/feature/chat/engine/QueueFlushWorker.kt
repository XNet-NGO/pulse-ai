package com.xnet.pulse.feature.chat.engine

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.xnet.pulse.feature.chat.db.ChatDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class QueueFlushWorker @AssistedInject constructor(
  @Assisted ctx: Context,
  @Assisted params: WorkerParameters,
  private val dao: ChatDao,
) : CoroutineWorker(ctx, params) {

  override suspend fun doWork(): Result {
    val queued = dao.getAllQueued()
    if (queued.isEmpty()) return Result.success()
    // Mark as sent — the ViewModel will pick them up on next load
    for (msg in queued) dao.updateStatus(msg.id, "sent")
    return Result.success()
  }

  companion object {
    fun enqueue(ctx: Context) {
      val request = OneTimeWorkRequestBuilder<QueueFlushWorker>()
        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
      WorkManager.getInstance(ctx).enqueueUniqueWork("queue_flush", ExistingWorkPolicy.REPLACE, request)
    }
  }
}
