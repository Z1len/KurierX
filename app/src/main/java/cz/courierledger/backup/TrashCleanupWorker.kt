package cz.courierledger.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import cz.courierledger.CourierLedgerApp

class TrashCleanupWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = runCatching {
        (applicationContext as CourierLedgerApp).repository.purgeExpiredTrash()
        Result.success()
    }.getOrElse { Result.retry() }
}
