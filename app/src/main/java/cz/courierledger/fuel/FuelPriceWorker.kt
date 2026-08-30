package cz.courierledger.fuel

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class FuelPriceWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = runCatching {
        FuelEstimator(applicationContext).refreshOfficialDieselPrice()
            ?: error("ČEPRO price page did not expose a usable diesel price")
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
}
