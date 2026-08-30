package cz.courierledger.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow


data class CustomerOrderRow(
    val orderId: Long,
    val routeId: Long,
    val routeDate: String,
    val routeType: RouteType,
    val warehouse: Warehouse,
    val customerId: Long,
    val firstName: String,
    val lastName: String,
    val displayAddress: String,
    val packages: Int,
    val tipHellers: Long
)

data class RouteCustomerRow(
    val orderId: Long,
    val customerId: Long,
    val firstName: String,
    val lastName: String,
    val displayAddress: String,
    val normalizedAddress: String,
    val packages: Int,
    val tipHellers: Long,
    val mergeGroupId: Long?
)

@Dao
interface CourierDao {
    @Insert suspend fun insertCalendar(v: CalendarEntryEntity): Long
    @Insert suspend fun insertCalendars(v: List<CalendarEntryEntity>): List<Long>
    @Update suspend fun updateCalendar(v: CalendarEntryEntity)
    @Query("SELECT * FROM calendar_entries WHERE deletedAt IS NULL ORDER BY date, plannedStartMinutes") fun observeCalendar(): Flow<List<CalendarEntryEntity>>
    @Query("SELECT * FROM calendar_entries WHERE date=:date AND deletedAt IS NULL ORDER BY plannedStartMinutes") suspend fun calendarForDate(date: String): List<CalendarEntryEntity>
    @Query("SELECT * FROM calendar_entries WHERE date BETWEEN :from AND :to AND deletedAt IS NULL ORDER BY date, plannedStartMinutes") suspend fun calendarBetween(from: String, to: String): List<CalendarEntryEntity>
    @Query("UPDATE calendar_entries SET deletedAt=:deletedAt WHERE date=:date AND deletedAt IS NULL") suspend fun softDeleteCalendarDate(date: String, deletedAt: Long)
    @Query("UPDATE calendar_entries SET deletedAt=:deletedAt WHERE date BETWEEN :from AND :to AND deletedAt IS NULL") suspend fun softDeleteCalendarRange(from: String, to: String, deletedAt: Long)

    @Insert suspend fun insertShift(v: ShiftEntity): Long
    @Update suspend fun updateShift(v: ShiftEntity)
    @Query("SELECT * FROM shifts WHERE id=:id LIMIT 1") suspend fun shift(id: Long): ShiftEntity?
    @Query("SELECT * FROM shifts WHERE status='ACTIVE' AND deletedAt IS NULL LIMIT 1") fun observeActiveShift(): Flow<ShiftEntity?>
    @Query("SELECT * FROM shifts WHERE status='ACTIVE' AND deletedAt IS NULL LIMIT 1") suspend fun activeShift(): ShiftEntity?
    @Query("SELECT * FROM shifts WHERE date=:date AND deletedAt IS NULL LIMIT 1") suspend fun shiftFor(date: String): ShiftEntity?
    @Query("SELECT * FROM shifts WHERE deletedAt IS NULL ORDER BY date DESC") fun observeShifts(): Flow<List<ShiftEntity>>
    @Query("SELECT * FROM shifts WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeDeletedShifts(): Flow<List<ShiftEntity>>
    @Query("UPDATE shifts SET deletedAt=:deletedAt WHERE id=:id AND deletedAt IS NULL") suspend fun softDeleteShift(id: Long, deletedAt: Long)
    @Query("UPDATE shifts SET deletedAt=NULL WHERE id=:id AND deletedAt=:deletedAt") suspend fun restoreShift(id: Long, deletedAt: Long)
    @Query("DELETE FROM shifts WHERE id=:id AND deletedAt IS NOT NULL") suspend fun hardDeleteShift(id: Long)
    @Query("SELECT id FROM shifts WHERE deletedAt IS NOT NULL AND deletedAt<:before") suspend fun expiredDeletedShiftIds(before: Long): List<Long>

    @Insert suspend fun insertRoute(v: RouteEntity): Long
    @Update suspend fun updateRoute(v: RouteEntity)
    @Query("SELECT r.* FROM routes r INNER JOIN shifts s ON s.id=r.shiftId WHERE r.shiftId=:shiftId AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY r.id") fun observeRoutesForShift(shiftId: Long): Flow<List<RouteEntity>>
    @Query("SELECT r.* FROM routes r INNER JOIN shifts s ON s.id=r.shiftId WHERE r.shiftId=:shiftId AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY r.id") suspend fun routesForShift(shiftId: Long): List<RouteEntity>
    @Query("SELECT * FROM routes WHERE id=:id LIMIT 1") suspend fun route(id: Long): RouteEntity?
    @Query("SELECT r.* FROM routes r INNER JOIN shifts s ON s.id=r.shiftId WHERE r.id=:id AND r.deletedAt IS NULL AND s.deletedAt IS NULL LIMIT 1") suspend fun activeRoute(id: Long): RouteEntity?
    @Query("SELECT r.* FROM routes r INNER JOIN shifts s ON s.id=r.shiftId WHERE r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY r.id DESC LIMIT 1") suspend fun latestRoute(): RouteEntity?

    @Query("SELECT r.* FROM routes r INNER JOIN shifts s ON s.id=r.shiftId WHERE r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY r.routeDate DESC, r.id DESC") fun observeAllRoutes(): Flow<List<RouteEntity>>
    @Query("SELECT * FROM routes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeDeletedRoutes(): Flow<List<RouteEntity>>
    @Query("UPDATE routes SET deletedAt=NULL WHERE id=:id") suspend fun restoreRoute(id: Long)
    @Query("DELETE FROM orders WHERE routeId=:routeId") suspend fun hardDeleteOrdersForRoute(routeId: Long)
    @Query("DELETE FROM merge_groups WHERE routeId=:routeId") suspend fun hardDeleteMergeGroupsForRoute(routeId: Long)
    @Query("DELETE FROM routes WHERE id=:routeId AND deletedAt IS NOT NULL") suspend fun hardDeleteRoute(routeId: Long)
    @Query("SELECT id FROM routes WHERE deletedAt IS NOT NULL AND deletedAt<:before") suspend fun expiredDeletedRouteIds(before: Long): List<Long>
    @Query("SELECT r.* FROM routes r INNER JOIN shifts s ON s.id=r.shiftId WHERE r.deletedAt IS NULL AND s.deletedAt IS NULL AND r.finishedAt IS NOT NULL AND r.finishedAt BETWEEN :fromMillis AND :toMillis ORDER BY r.finishedAt") suspend fun routesFinishedBetween(fromMillis: Long, toMillis: Long): List<RouteEntity>
    @Query("UPDATE routes SET deletedAt=:deletedAt WHERE shiftId=:shiftId AND deletedAt IS NULL") suspend fun softDeleteRoutesForShift(shiftId: Long, deletedAt: Long)
    @Query("UPDATE routes SET deletedAt=NULL WHERE shiftId=:shiftId AND deletedAt=:deletedAt") suspend fun restoreRoutesForShift(shiftId: Long, deletedAt: Long)

    @Insert suspend fun insertCustomer(v: CustomerEntity): Long
    @Update suspend fun updateCustomer(v: CustomerEntity)
    @Query("SELECT * FROM customers WHERE id=:id LIMIT 1") suspend fun customer(id: Long): CustomerEntity?
    @Insert suspend fun insertOrder(v: OrderEntity): Long
    @Update suspend fun updateOrder(v: OrderEntity)
    @Query("SELECT * FROM orders WHERE id=:id LIMIT 1") suspend fun order(id: Long): OrderEntity?
    @Query("UPDATE orders SET mergeGroupId=NULL WHERE id=:orderId") suspend fun clearOrderMergeGroup(orderId: Long)
    @Query("SELECT o.* FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE o.routeId=:routeId AND o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY o.id") suspend fun ordersForRoute(routeId: Long): List<OrderEntity>
    @Query("SELECT o.* FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE o.routeId=:routeId AND o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY o.id") fun observeOrdersForRoute(routeId: Long): Flow<List<OrderEntity>>
    @Query("SELECT o.* FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE r.shiftId=:shiftId AND o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY o.id") fun observeOrdersForShift(shiftId: Long): Flow<List<OrderEntity>>
    @Query("""
        SELECT o.id AS orderId, c.id AS customerId, c.firstName AS firstName, c.lastName AS lastName,
               c.displayAddress AS displayAddress, o.normalizedAddress AS normalizedAddress,
               o.packages AS packages, o.tipHellers AS tipHellers, o.mergeGroupId AS mergeGroupId
        FROM orders o INNER JOIN customers c ON c.id=o.customerId
        WHERE o.routeId=:routeId AND o.deletedAt IS NULL AND EXISTS(SELECT 1 FROM routes r INNER JOIN shifts s ON s.id=r.shiftId WHERE r.id=o.routeId AND r.deletedAt IS NULL AND s.deletedAt IS NULL)
        ORDER BY o.id
    """) fun observeRouteCustomers(routeId: Long): Flow<List<RouteCustomerRow>>
    @Query("SELECT COUNT(*) FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE o.routeId=:routeId AND o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL") suspend fun rawOrderCount(routeId: Long): Int
    @Query("""
        SELECT o.id AS orderId, o.routeId AS routeId, r.routeDate AS routeDate, r.routeType AS routeType, r.warehouse AS warehouse, c.id AS customerId,
               c.firstName AS firstName, c.lastName AS lastName, c.displayAddress AS displayAddress,
               o.packages AS packages, o.tipHellers AS tipHellers
        FROM orders o INNER JOIN customers c ON c.id=o.customerId
        INNER JOIN routes r ON r.id=o.routeId
        INNER JOIN shifts s ON s.id=r.shiftId
        WHERE o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL
        ORDER BY r.routeDate DESC, r.id, o.id
    """) fun observeCustomerOrders(): Flow<List<CustomerOrderRow>>

    @Insert suspend fun insertMergeGroup(v: MergeGroupEntity): Long
    @Update suspend fun updateMergeGroup(v: MergeGroupEntity)
    @Query("SELECT mg.* FROM merge_groups mg INNER JOIN routes r ON r.id=mg.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE mg.routeId=:routeId AND mg.active=1 AND r.deletedAt IS NULL AND s.deletedAt IS NULL") suspend fun activeMergeGroups(routeId: Long): List<MergeGroupEntity>
    @Query("SELECT mg.* FROM merge_groups mg INNER JOIN routes r ON r.id=mg.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE mg.routeId=:routeId AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY mg.id") suspend fun allMergeGroups(routeId: Long): List<MergeGroupEntity>
    @Query("SELECT mg.* FROM merge_groups mg INNER JOIN routes r ON r.id=mg.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE mg.routeId=:routeId AND mg.active=1 AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY mg.id") fun observeActiveMergeGroups(routeId: Long): Flow<List<MergeGroupEntity>>
    @Query("SELECT * FROM merge_groups WHERE id=:groupId LIMIT 1") suspend fun mergeGroup(groupId: Long): MergeGroupEntity?
    @Query("UPDATE merge_groups SET active=0 WHERE id=:groupId") suspend fun deactivateMergeGroup(groupId: Long)
    @Query("UPDATE merge_groups SET active=0 WHERE id IN (:groupIds)") suspend fun deactivateMergeGroups(groupIds: List<Long>)
    @Query("UPDATE orders SET mergeGroupId=:groupId WHERE id IN (:orderIds)") suspend fun assignMergeGroup(orderIds: List<Long>, groupId: Long)
    @Query("UPDATE orders SET mergeGroupId=NULL WHERE mergeGroupId=:groupId") suspend fun clearMergeGroup(groupId: Long)
    @Query("SELECT COUNT(*) FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE o.mergeGroupId=:groupId AND o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL") suspend fun mergeGroupMemberCount(groupId: Long): Int
    @Delete suspend fun deleteMergeGroup(v: MergeGroupEntity)

    @Insert suspend fun insertFinancial(v: FinancialEntryEntity): Long
    @Update suspend fun updateFinancial(v: FinancialEntryEntity)
    @Query("SELECT * FROM financial_entries WHERE deletedAt IS NULL ORDER BY date DESC, id DESC") fun observeFinancial(): Flow<List<FinancialEntryEntity>>
    @Query("SELECT * FROM financial_entries WHERE date BETWEEN :from AND :to AND deletedAt IS NULL ORDER BY date DESC, id DESC") suspend fun financialBetween(from: String, to: String): List<FinancialEntryEntity>
    @Query("UPDATE financial_entries SET deletedAt=:deletedAt WHERE id=:id AND deletedAt IS NULL") suspend fun softDeleteFinancial(id: Long, deletedAt: Long)
    @Query("SELECT * FROM financial_entries WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeDeletedFinancial(): Flow<List<FinancialEntryEntity>>
    @Query("UPDATE financial_entries SET deletedAt=NULL WHERE id=:id") suspend fun restoreFinancial(id: Long)
    @Query("DELETE FROM financial_entries WHERE id=:id AND deletedAt IS NOT NULL") suspend fun hardDeleteFinancial(id: Long)
    @Query("DELETE FROM financial_entries WHERE deletedAt IS NOT NULL AND deletedAt<:before") suspend fun purgeFinancial(before: Long)

    @Insert suspend fun insertAdvance(v: AdvanceEntity): Long
    @Update suspend fun updateAdvance(v: AdvanceEntity)
    @Query("SELECT * FROM advances WHERE deletedAt IS NULL ORDER BY date DESC, id DESC") fun observeAdvances(): Flow<List<AdvanceEntity>>
    @Query("SELECT * FROM advances WHERE date BETWEEN :from AND :to AND deletedAt IS NULL ORDER BY date DESC, id DESC") suspend fun advancesBetween(from: String, to: String): List<AdvanceEntity>
    @Query("UPDATE advances SET deletedAt=:deletedAt WHERE id=:id AND deletedAt IS NULL") suspend fun softDeleteAdvance(id: Long, deletedAt: Long)
    @Query("SELECT * FROM advances WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC") fun observeDeletedAdvances(): Flow<List<AdvanceEntity>>
    @Query("UPDATE advances SET deletedAt=NULL WHERE id=:id") suspend fun restoreAdvance(id: Long)
    @Query("DELETE FROM advances WHERE id=:id AND deletedAt IS NOT NULL") suspend fun hardDeleteAdvance(id: Long)
    @Query("DELETE FROM advances WHERE deletedAt IS NOT NULL AND deletedAt<:before") suspend fun purgeAdvances(before: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFuel(v: FuelExpenseEntity)
    @Query("SELECT * FROM fuel_expenses ORDER BY month DESC") fun observeFuelExpenses(): Flow<List<FuelExpenseEntity>>
    @Query("SELECT * FROM fuel_expenses WHERE month=:month LIMIT 1") suspend fun fuel(month: String): FuelExpenseEntity?
    @Insert suspend fun insertRateRule(v: RateRuleEntity): Long
    @Query("SELECT * FROM rate_rules WHERE startDate<=:date AND endDate>=:date ORDER BY priority DESC, id DESC LIMIT 1") suspend fun rateFor(date: String): RateRuleEntity?
    @Insert suspend fun insertSalaryPayment(v: SalaryPaymentEntity): Long
    @Update suspend fun updateSalaryPayment(v: SalaryPaymentEntity)
    @Query("SELECT * FROM salary_payments ORDER BY receivedDate DESC, id DESC") fun observeSalaryPayments(): Flow<List<SalaryPaymentEntity>>
    @Query("SELECT * FROM salary_payments WHERE id=:id LIMIT 1") suspend fun salaryPayment(id: Long): SalaryPaymentEntity?
    @Query("DELETE FROM salary_payments WHERE id=:id") suspend fun deleteSalaryPayment(id: Long)
    @Query("SELECT * FROM salary_payments WHERE periodStart<=:to AND periodEnd>=:from") suspend fun paymentsOverlapping(from: String, to: String): List<SalaryPaymentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertGoal(v: GoalEntity)
    @Query("SELECT * FROM goals WHERE month=:month LIMIT 1") suspend fun goal(month: String): GoalEntity?
    @Query("SELECT * FROM goals ORDER BY month DESC") fun observeGoals(): Flow<List<GoalEntity>>

    @Insert suspend fun insertSourcePhoto(v: SourcePhotoEntity): Long
    @Query("SELECT * FROM source_photos WHERE sha256=:sha LIMIT 1") suspend fun photoByHash(sha: String): SourcePhotoEntity?
    @Insert suspend fun insertOcrResult(v: OcrResultEntity): Long
    @Query("SELECT * FROM ocr_results WHERE photoId=:photoId AND kind=:kind ORDER BY id DESC LIMIT 1") suspend fun latestOcrForPhoto(photoId: Long, kind: String): OcrResultEntity?
    @Insert suspend fun insertSnapshot(v: StatisticsSnapshotEntity): Long
    @Query("SELECT * FROM statistics_snapshots ORDER BY capturedAt DESC LIMIT 2") suspend fun latestSnapshots(): List<StatisticsSnapshotEntity>
    @Query("SELECT * FROM statistics_snapshots ORDER BY capturedAt DESC") fun observeSnapshots(): Flow<List<StatisticsSnapshotEntity>>

    @Insert suspend fun audit(v: AuditLogEntity): Long
    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT :limit OFFSET :offset") suspend fun auditPage(limit: Int, offset: Int): List<AuditLogEntity>
    @Query("SELECT * FROM audit_log ORDER BY createdAt DESC LIMIT 500") fun observeAudit(): Flow<List<AuditLogEntity>>
    @Insert suspend fun insertNotification(v: AppNotificationEntity): Long
    @Query("SELECT * FROM app_notifications WHERE active=1 ORDER BY createdAt DESC") fun observeNotifications(): Flow<List<AppNotificationEntity>>
    @Query("UPDATE app_notifications SET active=0 WHERE id=:id") suspend fun dismissNotification(id: Long)
    @Query("UPDATE app_notifications SET active=0 WHERE linkedEntityType='Route' AND linkedEntityId=:routeId") suspend fun dismissRouteNotifications(routeId: Long)
    @Query("UPDATE app_notifications SET active=0 WHERE linkedEntityType='Route' AND linkedEntityId IN (SELECT id FROM routes WHERE shiftId=:shiftId)") suspend fun dismissRouteNotificationsForShift(shiftId: Long)
    @Query("SELECT * FROM app_notifications WHERE kind=:kind AND linkedEntityType=:entityType AND linkedEntityId=:entityId AND active=1 LIMIT 1") suspend fun activeNotificationFor(kind: String, entityType: String, entityId: Long): AppNotificationEntity?

    @Query("""
        SELECT COALESCE(SUM(CASE WHEN o.mergeGroupId IS NULL OR NOT EXISTS(SELECT 1 FROM merge_groups mg WHERE mg.id=o.mergeGroupId AND mg.active=1) THEN 1 ELSE 0 END),0)
             + COUNT(DISTINCT CASE WHEN o.mergeGroupId IS NOT NULL AND EXISTS(SELECT 1 FROM merge_groups mg WHERE mg.id=o.mergeGroupId AND mg.active=1) THEN o.mergeGroupId END)
        FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId
        WHERE o.routeId=:routeId AND o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL
    """)
    suspend fun factualOrderCount(routeId: Long): Int

    @Query("SELECT COALESCE(SUM(o.tipHellers),0) FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE o.routeId=:routeId AND o.deletedAt IS NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL") suspend fun tipsForRoute(routeId: Long): Long

    @Query("UPDATE orders SET deletedAt=:deletedAt WHERE routeId=:routeId AND deletedAt IS NULL") suspend fun softDeleteOrdersForRoute(routeId: Long, deletedAt: Long)
    @Query("UPDATE orders SET deletedAt=NULL WHERE routeId=:routeId AND deletedAt=:deletedAt") suspend fun restoreOrdersForRoute(routeId: Long, deletedAt: Long)
    @Query("UPDATE orders SET deletedAt=:deletedAt WHERE routeId IN (SELECT id FROM routes WHERE shiftId=:shiftId AND deletedAt=:deletedAt) AND deletedAt IS NULL") suspend fun softDeleteOrdersForShift(shiftId: Long, deletedAt: Long)
    @Query("UPDATE orders SET deletedAt=NULL WHERE routeId IN (SELECT id FROM routes WHERE shiftId=:shiftId AND deletedAt IS NULL) AND deletedAt=:deletedAt") suspend fun restoreOrdersForShift(shiftId: Long, deletedAt: Long)
    @Query("UPDATE orders SET deletedAt=:deletedAt WHERE id=:orderId AND deletedAt IS NULL") suspend fun softDeleteOrder(orderId: Long, deletedAt: Long)
    @Query("UPDATE orders SET deletedAt=NULL WHERE id=:orderId AND deletedAt=:deletedAt") suspend fun restoreOrder(orderId: Long, deletedAt: Long)
    @Query("DELETE FROM orders WHERE id=:orderId AND deletedAt IS NOT NULL") suspend fun hardDeleteOrder(orderId: Long)
    @Query("SELECT id FROM orders WHERE deletedAt IS NOT NULL AND deletedAt<:before") suspend fun expiredDeletedOrderIds(before: Long): List<Long>
    @Query("SELECT COUNT(*) FROM orders WHERE customerId=:customerId") suspend fun orderCountForCustomer(customerId: Long): Int
    @Query("DELETE FROM customers WHERE id=:customerId") suspend fun hardDeleteCustomer(customerId: Long)
    @Query("SELECT o.* FROM orders o INNER JOIN routes r ON r.id=o.routeId INNER JOIN shifts s ON s.id=r.shiftId WHERE o.deletedAt IS NOT NULL AND r.deletedAt IS NULL AND s.deletedAt IS NULL ORDER BY o.deletedAt DESC") fun observeDeletedOrders(): Flow<List<OrderEntity>>
}
