package cz.courierledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import cz.courierledger.db.*
import cz.courierledger.domain.CourierRepository
import cz.courierledger.domain.EarningsCalculator
import cz.courierledger.domain.RouteMoneyInput
import cz.courierledger.domain.ReconciliationResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

data class RouteUiSummary(
    val route: RouteEntity,
    val clients: Int,
    val factualOrders: Int,
    val mergedGroups: Int,
    val tipsHellers: Long,
    val baseHellers: Long,
    val regionBonusHellers: Long,
    val grossHellers: Long
)

data class MainUiState(
    val activeShift: ShiftEntity? = null,
    val displayShift: ShiftEntity? = null,
    val activeRoutes: List<RouteEntity> = emptyList(),
    val routeSummaries: List<RouteUiSummary> = emptyList(),
    val calendar: List<CalendarEntryEntity> = emptyList(),
    val notifications: List<AppNotificationEntity> = emptyList(),
    val lastReconciliation: ReconciliationResult? = null,
    val error: String? = null
) {
    val completedRings: Int get() = activeRoutes.sumOf { EarningsCalculator.rings(it.routeType) }
    val factualOrders: Int get() = routeSummaries.sumOf { it.factualOrders }
    val tipsHellers: Long get() = routeSummaries.sumOf { it.tipsHellers }
    val routeGrossHellers: Long get() = routeSummaries.sumOf { it.grossHellers }
}

class MainViewModel(private val repo: CourierRepository) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)
    private val lastReconciliation = MutableStateFlow<ReconciliationResult?>(null)

    private val shifts: Flow<List<ShiftEntity>> = repo.dao.observeShifts()

    private val displayShift: Flow<ShiftEntity?> = combine(repo.activeShift, shifts) { active, all ->
        active ?: all.firstOrNull { it.date == LocalDate.now().toString() } ?: all.firstOrNull()
    }.distinctUntilChangedBy { it?.id }

    private val activeRoutes: Flow<List<RouteEntity>> = displayShift.flatMapLatest { shift ->
        if (shift == null) flowOf(emptyList()) else repo.dao.observeRoutesForShift(shift.id)
    }

    private val activeOrders: Flow<List<OrderEntity>> = displayShift.flatMapLatest { shift ->
        if (shift == null) flowOf(emptyList()) else repo.dao.observeOrdersForShift(shift.id)
    }

    private val routeSummaries: Flow<List<RouteUiSummary>> = combine(activeRoutes, activeOrders) { routes, orders -> routes to orders }
        .mapLatest { (routes, orders) ->
            routes.map { route ->
                // DAO queries are the single source of truth for deletion + merge semantics.
                // `orders` remains part of combine only to make Room changes instantly reactive.
                val factual = repo.dao.factualOrderCount(route.id)
                val clients = repo.dao.rawOrderCount(route.id)
                val tips = repo.dao.tipsForRoute(route.id)
                val activeGroups = repo.dao.activeMergeGroups(route.id).size
                val rateRule = repo.dao.rateFor(route.routeDate)
                val result = EarningsCalculator.route(
                    RouteMoneyInput(
                        date = LocalDate.parse(route.routeDate),
                        type = route.routeType,
                        factualOrders = factual,
                        tipsHellers = tips,
                        weekdayRateHellers = rateRule?.mondayThursdayHellers ?: 5_000,
                        weekendRateHellers = rateRule?.fridaySundayHellers ?: 8_000
                    )
                )
                RouteUiSummary(
                    route = route,
                    clients = clients,
                    factualOrders = factual,
                    mergedGroups = activeGroups,
                    tipsHellers = tips,
                    baseHellers = result.base.hellers,
                    regionBonusHellers = result.regionBonus.hellers,
                    grossHellers = result.gross.hellers
                )
            }
        }

    private val shiftPair: Flow<Pair<ShiftEntity?, ShiftEntity?>> = combine(repo.activeShift, displayShift) { active, shown -> active to shown }

    private val coreState: Flow<MainUiState> = combine(
        shiftPair,
        activeRoutes,
        routeSummaries,
        repo.calendar,
        repo.notifications
    ) { shiftsPair, routes, summaries, calendar, notifications ->
        MainUiState(activeShift = shiftsPair.first, displayShift = shiftsPair.second, activeRoutes = routes, routeSummaries = summaries, calendar = calendar, notifications = notifications)
    }

    val state: StateFlow<MainUiState> = combine(coreState, error, lastReconciliation) { core, currentError, reconciliation ->
        core.copy(error = currentError, lastReconciliation = reconciliation)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainUiState())

    fun startShift() = launch {
        lastReconciliation.value = null
        repo.startShift(LocalDate.now())
    }

    fun updatePlan(plannedRings: Int) = launch { repo.updateActiveShiftPlan(plannedRings) }

    fun closeShift(comment: String?) = launch {
        val shift = state.value.activeShift ?: return@launch
        lastReconciliation.value = repo.closeShift(shift.id, comment)
    }

    fun dismissNotification(id: Long) = launch { repo.dismissNotification(id) }

    fun clearError() { error.value = null }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        runCatching { block() }.onFailure { error.value = it.message ?: "Неизвестная ошибка" }
    }

    class Factory(private val repo: CourierRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(repo) as T
    }
}
