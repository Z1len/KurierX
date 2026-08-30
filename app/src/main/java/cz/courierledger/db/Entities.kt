package cz.courierledger.db

import androidx.room.*

enum class Warehouse { LIBOC, CHRASTANY, HORNI_POCERNICE }
enum class RouteType { OT, REGION, EXPRESS }
enum class DataSource { OCR, MANUAL, AUTO_CALC, IMPORT, USER_CORRECTION }
enum class ShiftStatus { PLANNED, ACTIVE, COMPLETE, PARTIAL }
enum class FinancialType { BONUS, COMPENSATION, PENALTY }

@Entity(tableName = "calendar_entries", indices = [Index("date")])
data class CalendarEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val plannedStartMinutes: Int,
    val plannedRings: Int,
    val warehouse: Warehouse = Warehouse.LIBOC,
    val source: DataSource = DataSource.MANUAL,
    val deletedAt: Long? = null
)

@Entity(tableName = "shifts", indices = [Index("date"), Index("status")])
data class ShiftEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val plannedRings: Int,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val status: ShiftStatus = ShiftStatus.PLANNED,
    val underfulfillmentComment: String? = null,
    val source: DataSource = DataSource.AUTO_CALC,
    val deletedAt: Long? = null
)

@Entity(tableName = "routes", foreignKeys = [ForeignKey(entity = ShiftEntity::class, parentColumns = ["id"], childColumns = ["shiftId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("shiftId"), Index("routeDate")])
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shiftId: Long,
    val routeDate: String,
    val routeType: RouteType,
    val warehouse: Warehouse,
    val externalRouteId: String? = null,
    val reportedOrderCount: Int? = null,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val confirmed: Boolean = false,
    val sourcePhotoId: Long? = null,
    val source: DataSource = DataSource.OCR,
    val deletedAt: Long? = null
)

@Entity(tableName = "customers", indices = [Index(value=["normalizedAddress"]), Index(value=["lastName", "firstName"])])
data class CustomerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val firstName: String,
    val lastName: String,
    val normalizedAddress: String,
    val displayAddress: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "orders", foreignKeys = [
    ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.RESTRICT),
    ForeignKey(entity = CustomerEntity::class, parentColumns = ["id"], childColumns = ["customerId"], onDelete = ForeignKey.RESTRICT)
], indices = [Index("routeId"), Index("customerId"), Index(value=["routeId", "normalizedAddress"])])
data class OrderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val customerId: Long,
    val rawAddress: String,
    val normalizedAddress: String,
    val packages: Int = 0,
    val tipHellers: Long = 0,
    val mergeGroupId: Long? = null,
    val sourcePhotoId: Long? = null,
    val source: DataSource = DataSource.OCR,
    val deletedAt: Long? = null
)

@Entity(tableName = "merge_groups", foreignKeys = [ForeignKey(entity = RouteEntity::class, parentColumns = ["id"], childColumns = ["routeId"], onDelete = ForeignKey.RESTRICT)], indices=[Index("routeId")])
data class MergeGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routeId: Long,
    val reason: String,
    val normalizedAddress: String,
    val active: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "statistics_snapshots", indices=[Index("capturedAt")])
data class StatisticsSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val capturedAt: Long,
    val cumulativeOrders: Int,
    val cumulativeTipsHellers: Long? = null,
    val rawText: String,
    val sourcePhotoId: Long? = null
)

@Entity(tableName = "financial_entries", indices=[Index("date"), Index("type")])
data class FinancialEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: FinancialType,
    val amountHellers: Long,
    val date: String,
    val description: String,
    val source: DataSource,
    val linkedRouteId: Long? = null,
    val deletedAt: Long? = null
)

@Entity(tableName = "advances", indices=[Index("date")])
data class AdvanceEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val amountHellers: Long, val date: String, val comment: String? = null, val source: DataSource = DataSource.MANUAL, val deletedAt: Long? = null)

@Entity(tableName = "fuel_expenses")
data class FuelExpenseEntity(@PrimaryKey val month: String, val amountHellers: Long = 350_000, val source: DataSource = DataSource.AUTO_CALC)

@Entity(tableName = "rate_rules", indices=[Index("startDate"), Index("endDate")])
data class RateRuleEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val startDate: String, val endDate: String, val mondayThursdayHellers: Long = 5_000, val fridaySundayHellers: Long = 8_000, val priority: Int = 0, val source: DataSource = DataSource.MANUAL)

@Entity(tableName = "salary_payments", indices=[Index("receivedDate")])
data class SalaryPaymentEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val receivedDate: String, val amountHellers: Long, val periodStart: String, val periodEnd: String, val comment: String? = null, val payslipPhotoId: Long? = null)

@Entity(tableName = "goals")
data class GoalEntity(@PrimaryKey val month: String, val targetOrders: Int)

@Entity(tableName = "source_photos", indices=[Index("createdAt"), Index("sha256", unique = true)])
data class SourcePhotoEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val uri: String, val localPath: String?, val sha256: String, val createdAt: Long, val kind: String, val retentionUntil: Long? = null)

@Entity(tableName = "ocr_results", foreignKeys=[ForeignKey(entity=SourcePhotoEntity::class,parentColumns=["id"],childColumns=["photoId"],onDelete=ForeignKey.RESTRICT)], indices=[Index("photoId")])
data class OcrResultEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val photoId: Long, val kind: String, val rawText: String, val confidence: Double, val parsedJson: String, val createdAt: Long = System.currentTimeMillis())

@Entity(tableName = "audit_log", indices=[Index("createdAt"), Index("entityType", "entityId")])
data class AuditLogEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val createdAt: Long = System.currentTimeMillis(), val action: String, val entityType: String, val entityId: String, val oldValue: String?, val newValue: String?, val source: DataSource)

@Entity(tableName = "app_notifications", indices=[Index("active"), Index("createdAt")])
data class AppNotificationEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val kind: String, val title: String, val message: String, val createdAt: Long = System.currentTimeMillis(), val active: Boolean = true, val linkedEntityType: String? = null, val linkedEntityId: Long? = null)
