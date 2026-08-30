package cz.courierledger.domain

import cz.courierledger.db.RouteEntity
import cz.courierledger.db.ShiftEntity

data class RouteHistorySummary(
    val route: RouteEntity,
    val clients: Int,
    val factualOrders: Int,
    val mergeGroups: Int,
    val tipsHellers: Long,
    val baseHellers: Long,
    val regionBonusHellers: Long,
    val grossHellers: Long
)

data class ShiftHistorySummary(
    val shift: ShiftEntity,
    val routes: List<RouteHistorySummary>,
    val plannedRings: Int,
    val completedRings: Int,
    val clients: Int,
    val factualOrders: Int,
    val tipsHellers: Long,
    val grossHellers: Long
) {
    val workedMillis: Long
        get() {
            val start = shift.startedAt ?: return 0L
            val end = shift.endedAt ?: System.currentTimeMillis()
            return (end - start).coerceAtLeast(0L)
        }
}


data class PeriodStatistics(
    val fromDate: String?,
    val toDate: String?,
    val shifts: Int,
    val factualOrders: Int,
    val clients: Int,
    val rings: Int,
    val ot: Int,
    val region: Int,
    val express: Int,
    val baseHellers: Long,
    val regionBonusHellers: Long,
    val tipsHellers: Long,
    val grossHellers: Long,
    val workedMillis: Long
) {
    val kcPerOrderHellers: Long get() = if (factualOrders == 0) 0 else grossHellers / factualOrders
    val ordersPerHour: Double get() = if (workedMillis <= 0L) 0.0 else factualOrders * 3_600_000.0 / workedMillis
    val ringsPerHour: Double get() = if (workedMillis <= 0L) 0.0 else rings * 3_600_000.0 / workedMillis
    val kcPerHourHellers: Long get() = if (workedMillis <= 0L) 0 else (grossHellers * 3_600_000L / workedMillis)
    val avgTipPerClientHellers: Long get() = if (clients == 0) 0 else tipsHellers / clients
    val avgTipPerOrderHellers: Long get() = if (factualOrders == 0) 0 else tipsHellers / factualOrders
}

data class StatisticsSnapshotComparison(
    val previous: cz.courierledger.db.StatisticsSnapshotEntity?,
    val current: cz.courierledger.db.StatisticsSnapshotEntity?,
    val cumulativeDelta: Int?,
    val routeOrdersBetween: Int?,
    val matches: Boolean?,
    val cumulativeTipsDeltaHellers: Long? = null,
    val routeTipsBetweenHellers: Long? = null,
    val tipsMatch: Boolean? = null
)

data class SalaryReconciliationSummary(
    val fromDate: String,
    val toDate: String,
    val expectedHellers: Long,
    val actualHellers: Long,
    val differenceHellers: Long
)

data class GoalProgress(
    val month: String,
    val targetOrders: Int?,
    val completedOrders: Int,
    val remainingOrders: Int,
    val remainingWorkDays: Int,
    val requiredPerWorkDay: Double
)


data class AnalyticsOverview(
    val topDay: ShiftHistorySummary?,
    val bottomDay: ShiftHistorySummary?,
    val longestShift: ShiftHistorySummary?,
    val shortestShift: ShiftHistorySummary?,
    val mostOrdersDay: ShiftHistorySummary?,
    val leastOrdersDay: ShiftHistorySummary?,
    val topTipCustomerName: String?,
    val topTipCustomerHellers: Long,
    val topTipDay: String?,
    val topTipDayHellers: Long,
    val topTipMonth: String?,
    val topTipMonthHellers: Long
)
