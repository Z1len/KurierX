package cz.courierledger

import android.app.Application
import androidx.work.*
import cz.courierledger.db.AppDatabase
import cz.courierledger.domain.CourierRepository
import cz.courierledger.security.DatabaseKeyManager
import java.util.concurrent.TimeUnit
import cz.courierledger.ruian.RuianStreetIndex
import cz.courierledger.ruian.RuianSyncWorker

class CourierLedgerApp : Application() {
    lateinit var database: AppDatabase; private set
    lateinit var repository: CourierRepository; private set
    override fun onCreate() {
        super.onCreate()
        cz.courierledger.backup.BackupManager.applyPendingRestore(this)
        database = AppDatabase.get(this, DatabaseKeyManager(this))
        repository = CourierRepository(database, this)
        val request = PeriodicWorkRequestBuilder<cz.courierledger.backup.BackupWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build()).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("daily_backup", ExistingPeriodicWorkPolicy.KEEP, request)
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "trash_cleanup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<cz.courierledger.backup.TrashCleanupWorker>(24, TimeUnit.HOURS).build()
        )

        // Download the official ČÚZK RÚIAN street catalogue once, then refresh monthly.
        // The app remains usable offline; this index only improves OCR ambiguity handling.
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        if (!RuianStreetIndex(this).info().available) {
            WorkManager.getInstance(this).enqueueUniqueWork(
                "ruian_streets_initial", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RuianSyncWorker>().setConstraints(network).build()
            )
        }
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "ruian_streets_monthly", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RuianSyncWorker>(30, TimeUnit.DAYS).setConstraints(network).build()
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "cepro_diesel_price_daily", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<cz.courierledger.fuel.FuelPriceWorker>(24, TimeUnit.HOURS).setConstraints(network).build()
        )
    }
}
