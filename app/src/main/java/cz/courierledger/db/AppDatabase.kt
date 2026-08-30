package cz.courierledger.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cz.courierledger.security.DatabaseKeyManager
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [CalendarEntryEntity::class, ShiftEntity::class, RouteEntity::class, CustomerEntity::class,
        OrderEntity::class, MergeGroupEntity::class, StatisticsSnapshotEntity::class, FinancialEntryEntity::class,
        AdvanceEntity::class, FuelExpenseEntity::class, RateRuleEntity::class, SalaryPaymentEntity::class,
        GoalEntity::class, SourcePhotoEntity::class, OcrResultEntity::class, AuditLogEntity::class,
        AppNotificationEntity::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun courierDao(): CourierDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE statistics_snapshots ADD COLUMN cumulativeTipsHellers INTEGER")
            }
        }

        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context, keys: DatabaseKeyManager): AppDatabase = instance ?: synchronized(this) {
            instance ?: run {
                System.loadLibrary("sqlcipher")
                val factory = SupportOpenHelperFactory(keys.getOrCreateDatabasePassphrase())
                Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "courier-ledger.db")
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
