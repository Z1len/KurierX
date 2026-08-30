package cz.courierledger.ruian

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class RuianSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        RuianStreetIndex(applicationContext).updateFromOfficialSource()
        Result.success()
    }.getOrElse { Result.retry() }
}
