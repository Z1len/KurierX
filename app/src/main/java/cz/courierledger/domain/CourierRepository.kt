package cz.courierledger.domain

import androidx.room.withTransaction
import android.content.Context
import cz.courierledger.fuel.FuelEstimator
import cz.courierledger.settings.AppSettings
import cz.courierledger.db.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth

class CourierRepository(private val db: AppDatabase, private val context: Context) {
    private val settings = AppSettings(context)
    private val fuelEstimator = FuelEstimator(context)
    val dao = db.courierDao()
    val activeShift: Flow<ShiftEntity?> = dao.observeActiveShift()
    val calendar: Flow<List<CalendarEntryEntity>> = dao.observeCalendar()
    val notifications: Flow<List<AppNotificationEntity>> = dao.observeNotifications()
    val financialEntries: Flow<List<FinancialEntryEntity>> = dao.observeFinancial()
    val advances: Flow<List<AdvanceEntity>> = dao.observeAdvances()
    val fuelExpenses: Flow<List<FuelExpenseEntity>> = dao.observeFuelExpenses()
    val salaryPayments: Flow<List<SalaryPaymentEntity>> = dao.observeSalaryPayments()
    val goals: Flow<List<GoalEntity>> = dao.observeGoals()
    val auditLog: Flow<List<AuditLogEntity>> = dao.observeAudit()
    val deletedRoutes: Flow<List<RouteEntity>> = dao.observeDeletedRoutes()
    val deletedShifts: Flow<List<ShiftEntity>> = dao.observeDeletedShifts()
    val deletedOrders: Flow<List<OrderEntity>> = dao.observeDeletedOrders()
    val deletedFinancial: Flow<List<FinancialEntryEntity>> = dao.observeDeletedFinancial()
    val deletedAdvances: Flow<List<AdvanceEntity>> = dao.observeDeletedAdvances()
    private val _dataRevision = MutableStateFlow(0L)
    val dataRevision: StateFlow<Long> = _dataRevision.asStateFlow()
    private fun touchData() { _dataRevision.value = _dataRevision.value + 1L }

    suspend fun startShift(date: LocalDate = LocalDate.now()): Long = db.withTransaction {
        dao.activeShift()?.let { return@withTransaction it.id }
        val plan = dao.calendarForDate(date.toString())
        val id = dao.insertShift(ShiftEntity(date=date.toString(), plannedRings=plan.sumOf { it.plannedRings }, startedAt=System.currentTimeMillis(), status=ShiftStatus.ACTIVE))
        dao.audit(AuditLogEntity(action="SHIFT_START",entityType="Shift",entityId=id.toString(),oldValue=null,newValue="started",source=DataSource.MANUAL))
        id
    }


    suspend fun replaceCalendarDate(date: LocalDate, entries: List<CalendarEntryEntity>, source: DataSource = DataSource.USER_CORRECTION) = db.withTransaction {
        val now = System.currentTimeMillis()
        val old = dao.calendarForDate(date.toString())
        dao.softDeleteCalendarDate(date.toString(), now)
        val clean = entries.map { entry ->
            require(entry.plannedStartMinutes in 0 until 24 * 60) { "Некорректное время смены" }
            require(entry.plannedRings in 1..20) { "Колечек должно быть от 1 до 20" }
            entry.copy(id = 0, date = date.toString(), source = source, deletedAt = null)
        }.sortedBy { it.plannedStartMinutes }
        if (clean.isNotEmpty()) dao.insertCalendars(clean)
        dao.audit(AuditLogEntity(action="CALENDAR_DAY_EDIT", entityType="CalendarEntry", entityId=date.toString(), oldValue=old.joinToString(";") { "${it.plannedStartMinutes}/${it.plannedRings}/${it.warehouse}" }, newValue=clean.joinToString(";") { "${it.plannedStartMinutes}/${it.plannedRings}/${it.warehouse}" }, source=source))
        syncShiftPlanFromCalendar(date)
    }

    suspend fun replaceCalendarMonth(month: YearMonth, entries: List<CalendarEntryEntity>, source: DataSource = DataSource.IMPORT) = db.withTransaction {
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val now = System.currentTimeMillis()
        val old = dao.calendarBetween(from.toString(), to.toString())
        dao.softDeleteCalendarRange(from.toString(), to.toString(), now)
        val clean = entries.filter { YearMonth.from(LocalDate.parse(it.date)) == month }.map { entry ->
            require(entry.plannedStartMinutes in 0 until 24 * 60) { "Некорректное время смены ${entry.date}" }
            require(entry.plannedRings in 1..20) { "Некорректное количество колечек ${entry.date}" }
            entry.copy(id=0, source=source, deletedAt=null)
        }.sortedWith(compareBy<CalendarEntryEntity> { it.date }.thenBy { it.plannedStartMinutes })
        if (clean.isNotEmpty()) dao.insertCalendars(clean)
        dao.audit(AuditLogEntity(action="CALENDAR_MONTH_IMPORT", entityType="CalendarEntry", entityId=month.toString(), oldValue="${old.size} entries", newValue="${clean.size} entries", source=source))
        clean.map { LocalDate.parse(it.date) }.distinct().forEach { syncShiftPlanFromCalendar(it) }
        old.map { LocalDate.parse(it.date) }.distinct().filter { d -> clean.none { it.date == d.toString() } }.forEach { syncShiftPlanFromCalendar(it) }
    }

    private suspend fun syncShiftPlanFromCalendar(date: LocalDate) {
        val shift = dao.shiftFor(date.toString()) ?: return
        if (shift.status !in setOf(ShiftStatus.PLANNED, ShiftStatus.ACTIVE)) return
        if (shift.source == DataSource.USER_CORRECTION) return
        val rings = dao.calendarForDate(date.toString()).sumOf { it.plannedRings }
        dao.updateShift(shift.copy(plannedRings = rings, source = DataSource.AUTO_CALC))
    }

    suspend fun updateActiveShiftPlan(plannedRings: Int) = db.withTransaction {
        require(plannedRings in 1..20) { "План колечек должен быть от 1 до 20" }
        val shift = dao.activeShift() ?: error("Нет активной смены")
        val old = shift.plannedRings
        dao.updateShift(shift.copy(plannedRings = plannedRings, source = DataSource.USER_CORRECTION))
        dao.audit(AuditLogEntity(action="SHIFT_PLAN_EDIT", entityType="Shift", entityId=shift.id.toString(), oldValue=old.toString(), newValue=plannedRings.toString(), source=DataSource.USER_CORRECTION))
    }

    suspend fun addRoute(shiftId: Long, type: RouteType, warehouse: Warehouse, reportedOrders: Int?, externalId: String?, photoId: Long?): Long = db.withTransaction {
        val shift = dao.shift(shiftId) ?: error("Смена #$shiftId не найдена")
        require(shift.deletedAt == null) { "Смена удалена" }
        val id = dao.insertRoute(RouteEntity(shiftId=shiftId, routeDate=LocalDate.now().toString(), routeType=type, warehouse=warehouse, reportedOrderCount=reportedOrders, externalRouteId=externalId, finishedAt=System.currentTimeMillis(), confirmed=true, sourcePhotoId=photoId))
        dao.audit(AuditLogEntity(action="ROUTE_CREATE",entityType="Route",entityId=id.toString(),oldValue=null,newValue="${type.name}, orders=$reportedOrders",source=DataSource.OCR))
        id
    }

    suspend fun addCustomerOrder(routeId: Long, first: String, last: String, address: String, packages: Int, tipHellers: Long, photoId: Long?): Long = db.withTransaction {
        require(dao.activeRoute(routeId) != null) { "Трасса удалена или относится к удалённой смене" }
        val normalized = AddressNormalizer.normalize(address)
        val customerId = dao.insertCustomer(CustomerEntity(firstName=first,lastName=last,normalizedAddress=normalized,displayAddress=address))
        val orderId = dao.insertOrder(OrderEntity(routeId=routeId, customerId=customerId, rawAddress=address, normalizedAddress=normalized, packages=packages, tipHellers=tipHellers, sourcePhotoId=photoId))
        dao.audit(AuditLogEntity(action="ORDER_CREATE",entityType="Order",entityId=orderId.toString(),oldValue=null,newValue="$first $last | $address",source=DataSource.OCR))
        orderId
    }

    suspend fun closeShift(shiftId: Long, comment: String?): ReconciliationResult = db.withTransaction {
        val shift = dao.activeShift() ?: error("No active shift")
        require(shift.id == shiftId)
        val check = ReconciliationEngine(dao).checkShift(shift)
        val rings = dao.routesForShift(shiftId).sumOf { EarningsCalculator.rings(it.routeType) }
        val partial = rings < shift.plannedRings
        require(!partial || !comment.isNullOrBlank()) { "Комментарий обязателен при недовыполнении" }
        dao.updateShift(shift.copy(endedAt=System.currentTimeMillis(), status=if (partial) ShiftStatus.PARTIAL else ShiftStatus.COMPLETE, underfulfillmentComment=comment))
        dao.audit(AuditLogEntity(action="SHIFT_CLOSE",entityType="Shift",entityId=shiftId.toString(),oldValue="ACTIVE",newValue=if(partial)"PARTIAL" else "COMPLETE",source=DataSource.MANUAL))
        check
    }

    suspend fun latestRouteForCurrentWork(): Long? {
        val active = dao.activeShift()
        if (active != null) return dao.routesForShift(active.id).lastOrNull()?.id
        return dao.latestRoute()?.id
    }



    suspend fun addFinancialEntry(type: FinancialType, amountHellers: Long, date: LocalDate, description: String, source: DataSource = DataSource.MANUAL): Long = db.withTransaction {
        require(amountHellers >= 0) { "Сумма не может быть отрицательной" }
        val storedType = if (type == FinancialType.COMPENSATION) FinancialType.BONUS else type
        val id = dao.insertFinancial(FinancialEntryEntity(type=storedType, amountHellers=amountHellers, date=date.toString(), description=description.trim(), source=source))
        dao.audit(AuditLogEntity(action="FINANCIAL_CREATE", entityType="FinancialEntry", entityId=id.toString(), oldValue=null, newValue="${storedType.name}|${date}|$amountHellers|${description.trim()}", source=source))
        id
    }

    suspend fun updateFinancialEntry(entry: FinancialEntryEntity, type: FinancialType, amountHellers: Long, date: LocalDate, description: String) = db.withTransaction {
        require(entry.deletedAt == null) { "Запись уже удалена" }
        require(amountHellers >= 0) { "Сумма не может быть отрицательной" }
        val storedType = if (type == FinancialType.COMPENSATION) FinancialType.BONUS else type
        val oldValue = "${entry.type.name}|${entry.date}|${entry.amountHellers}|${entry.description}"
        val updated = entry.copy(type=storedType, amountHellers=amountHellers, date=date.toString(), description=description.trim(), source=DataSource.USER_CORRECTION)
        dao.updateFinancial(updated)
        dao.audit(AuditLogEntity(action="FINANCIAL_EDIT", entityType="FinancialEntry", entityId=entry.id.toString(), oldValue=oldValue, newValue="${storedType.name}|${date}|$amountHellers|${description.trim()}", source=DataSource.USER_CORRECTION))
    }

    suspend fun deleteFinancialEntry(entry: FinancialEntryEntity) = db.withTransaction {
        if (entry.deletedAt != null) return@withTransaction
        val now = System.currentTimeMillis()
        dao.softDeleteFinancial(entry.id, now)
        dao.audit(AuditLogEntity(action="FINANCIAL_DELETE_TO_TRASH", entityType="FinancialEntry", entityId=entry.id.toString(), oldValue="active", newValue="deletedAt=$now", source=DataSource.USER_CORRECTION))
    }

    suspend fun addAdvance(amountHellers: Long, date: LocalDate, comment: String?): Long = db.withTransaction {
        require(amountHellers > 0) { "Аванс должен быть больше 0" }
        val id = dao.insertAdvance(AdvanceEntity(amountHellers=amountHellers, date=date.toString(), comment=comment?.trim()?.ifBlank { null }))
        dao.audit(AuditLogEntity(action="ADVANCE_CREATE", entityType="Advance", entityId=id.toString(), oldValue=null, newValue="${date}|$amountHellers|${comment.orEmpty()}", source=DataSource.MANUAL))
        id
    }

    suspend fun updateAdvance(entry: AdvanceEntity, amountHellers: Long, date: LocalDate, comment: String?) = db.withTransaction {
        require(entry.deletedAt == null) { "Аванс уже удалён" }
        require(amountHellers > 0) { "Аванс должен быть больше 0" }
        val oldValue = "${entry.date}|${entry.amountHellers}|${entry.comment.orEmpty()}"
        dao.updateAdvance(entry.copy(amountHellers=amountHellers, date=date.toString(), comment=comment?.trim()?.ifBlank { null }, source=DataSource.USER_CORRECTION))
        dao.audit(AuditLogEntity(action="ADVANCE_EDIT", entityType="Advance", entityId=entry.id.toString(), oldValue=oldValue, newValue="${date}|$amountHellers|${comment.orEmpty()}", source=DataSource.USER_CORRECTION))
    }

    suspend fun deleteAdvance(entry: AdvanceEntity) = db.withTransaction {
        if (entry.deletedAt != null) return@withTransaction
        val now = System.currentTimeMillis()
        dao.softDeleteAdvance(entry.id, now)
        dao.audit(AuditLogEntity(action="ADVANCE_DELETE_TO_TRASH", entityType="Advance", entityId=entry.id.toString(), oldValue="active", newValue="deletedAt=$now", source=DataSource.USER_CORRECTION))
    }


    suspend fun addSalaryPayment(receivedDate: LocalDate, amountHellers: Long, periodStart: LocalDate, periodEnd: LocalDate, comment: String?, payslipPhotoId: Long?): Long = db.withTransaction {
        require(amountHellers >= 0) { "Сумма выплаты не может быть отрицательной" }
        require(!periodEnd.isBefore(periodStart)) { "Конец периода не может быть раньше начала" }
        val id = dao.insertSalaryPayment(SalaryPaymentEntity(receivedDate=receivedDate.toString(), amountHellers=amountHellers, periodStart=periodStart.toString(), periodEnd=periodEnd.toString(), comment=comment?.trim()?.ifBlank { null }, payslipPhotoId=payslipPhotoId))
        dao.audit(AuditLogEntity(action="SALARY_PAYMENT_CREATE", entityType="SalaryPayment", entityId=id.toString(), oldValue=null, newValue="${receivedDate}|$amountHellers|${periodStart}..${periodEnd}", source=DataSource.MANUAL))
        id
    }

    suspend fun updateSalaryPayment(entry: SalaryPaymentEntity, receivedDate: LocalDate, amountHellers: Long, periodStart: LocalDate, periodEnd: LocalDate, comment: String?, payslipPhotoId: Long?) = db.withTransaction {
        require(amountHellers >= 0) { "Сумма выплаты не может быть отрицательной" }
        require(!periodEnd.isBefore(periodStart)) { "Конец периода не может быть раньше начала" }
        val old = "${entry.receivedDate}|${entry.amountHellers}|${entry.periodStart}..${entry.periodEnd}|${entry.payslipPhotoId}"
        val updated = entry.copy(receivedDate=receivedDate.toString(), amountHellers=amountHellers, periodStart=periodStart.toString(), periodEnd=periodEnd.toString(), comment=comment?.trim()?.ifBlank { null }, payslipPhotoId=payslipPhotoId)
        dao.updateSalaryPayment(updated)
        dao.audit(AuditLogEntity(action="SALARY_PAYMENT_EDIT", entityType="SalaryPayment", entityId=entry.id.toString(), oldValue=old, newValue="${updated.receivedDate}|${updated.amountHellers}|${updated.periodStart}..${updated.periodEnd}|${updated.payslipPhotoId}", source=DataSource.USER_CORRECTION))
    }

    suspend fun deleteSalaryPayment(entry: SalaryPaymentEntity) = db.withTransaction {
        dao.deleteSalaryPayment(entry.id)
        dao.audit(AuditLogEntity(action="SALARY_PAYMENT_DELETE", entityType="SalaryPayment", entityId=entry.id.toString(), oldValue="${entry.receivedDate}|${entry.amountHellers}", newValue="deleted", source=DataSource.USER_CORRECTION))
    }

    suspend fun salaryReconciliation(from: LocalDate, to: LocalDate): SalaryReconciliationSummary {
        val monthGroups = generateSequence(YearMonth.from(from)) { it.plusMonths(1) }.takeWhile { !it.isAfter(YearMonth.from(to)) }.toList()
        var expected = 0L
        for (month in monthGroups) {
            val summary = monthSummary(month)
            expected += summary.expectedPayout.hellers
        }
        val actual = dao.paymentsOverlapping(from.toString(), to.toString()).sumOf { it.amountHellers }
        return SalaryReconciliationSummary(from.toString(), to.toString(), expected, actual, actual - expected)
    }

    suspend fun setGoal(month: YearMonth, targetOrders: Int) = db.withTransaction {
        require(targetOrders > 0) { "Цель должна быть больше 0" }
        val old = dao.goal(month.toString())
        dao.upsertGoal(GoalEntity(month.toString(), targetOrders))
        dao.audit(AuditLogEntity(action="GOAL_EDIT", entityType="Goal", entityId=month.toString(), oldValue=old?.targetOrders?.toString(), newValue=targetOrders.toString(), source=DataSource.USER_CORRECTION))
    }

    suspend fun goalProgress(month: YearMonth): GoalProgress {
        val goal = dao.goal(month.toString())
        val from = month.atDay(1)
        val to = month.atEndOfMonth()
        val stats = periodStatistics(from, to)
        val remaining = (goal?.targetOrders ?: 0) - stats.factualOrders
        val today = LocalDate.now()
        val remainingWorkDays = dao.calendarBetween(maxOf(from, today).toString(), to.toString()).map { it.date }.distinct().count { date -> date >= maxOf(from, today).toString() }
        val perDay = if (remaining > 0 && remainingWorkDays > 0) remaining.toDouble() / remainingWorkDays else 0.0
        return GoalProgress(month.toString(), goal?.targetOrders, stats.factualOrders, remaining.coerceAtLeast(0), remainingWorkDays, perDay)
    }

    suspend fun setFuelExpense(month: YearMonth, amountHellers: Long) = db.withTransaction {
        require(amountHellers >= 0) { "Расход на дизель не может быть отрицательным" }
        val old = dao.fuel(month.toString())
        dao.upsertFuel(FuelExpenseEntity(month=month.toString(), amountHellers=amountHellers, source=DataSource.USER_CORRECTION))
        dao.audit(AuditLogEntity(action="FUEL_EDIT", entityType="FuelExpense", entityId=month.toString(), oldValue=old?.amountHellers?.toString(), newValue=amountHellers.toString(), source=DataSource.USER_CORRECTION))
    }

    suspend fun updateCustomerOrder(
        orderId: Long,
        first: String,
        last: String,
        address: String,
        packages: Int,
        tipHellers: Long
    ) {
        require(address.isNotBlank()) { "Адрес не может быть пустым" }
        require(packages >= 0) { "Количество пакетов не может быть отрицательным" }
        require(tipHellers >= 0) { "Чаевые не могут быть отрицательными" }

        val routeId = db.withTransaction {
            val order = dao.order(orderId) ?: error("Заказ #$orderId не найден")
            require(order.deletedAt == null) { "Заказ уже удалён" }
            require(dao.activeRoute(order.routeId) != null) { "Трасса удалена или относится к удалённой смене" }
            val customer = dao.customer(order.customerId) ?: error("Клиент #${order.customerId} не найден")
            val normalized = AddressNormalizer.normalize(address)
            val addressChanged = normalized != order.normalizedAddress
            val oldGroupId = order.mergeGroupId

            if (addressChanged && oldGroupId != null) {
                dao.clearOrderMergeGroup(order.id)
                val group = dao.mergeGroup(oldGroupId)
                if (group != null && group.active && dao.mergeGroupMemberCount(oldGroupId) < 2) {
                    dao.clearMergeGroup(oldGroupId)
                    dao.deleteMergeGroup(group)
                }
            }

            val oldValue = "${customer.firstName} ${customer.lastName} | ${order.rawAddress} | packages=${order.packages} | tip=${order.tipHellers}"
            dao.updateCustomer(
                customer.copy(
                    firstName = first.trim(),
                    lastName = last.trim(),
                    normalizedAddress = normalized,
                    displayAddress = address.trim()
                )
            )
            dao.updateOrder(
                order.copy(
                    rawAddress = address.trim(),
                    normalizedAddress = normalized,
                    packages = packages,
                    tipHellers = tipHellers,
                    mergeGroupId = if (addressChanged) null else order.mergeGroupId,
                    source = DataSource.USER_CORRECTION
                )
            )
            val newValue = "${first.trim()} ${last.trim()} | ${address.trim()} | packages=$packages | tip=$tipHellers"
            dao.audit(
                AuditLogEntity(
                    action = "ORDER_EDIT",
                    entityType = "Order",
                    entityId = order.id.toString(),
                    oldValue = oldValue,
                    newValue = newValue,
                    source = DataSource.USER_CORRECTION
                )
            )
            order.routeId
        }
        MergeEngine(db).mergeExactAddresses(routeId)
        touchData()
    }


    suspend fun updateRouteDetails(
        routeId: Long,
        type: RouteType,
        warehouse: Warehouse,
        reportedOrders: Int?,
        externalId: String?
    ) = db.withTransaction {
        val route = dao.route(routeId) ?: error("Трасса #$routeId не найдена")
        require(route.deletedAt == null) { "Трасса уже удалена" }
        val oldValue = "${route.routeType.name}|${route.warehouse.name}|orders=${route.reportedOrderCount}|external=${route.externalRouteId.orEmpty()}"
        val updated = route.copy(
            routeType = type,
            warehouse = warehouse,
            reportedOrderCount = reportedOrders,
            externalRouteId = externalId?.trim()?.ifBlank { null },
            source = DataSource.USER_CORRECTION
        )
        dao.updateRoute(updated)
        val newValue = "${type.name}|${warehouse.name}|orders=$reportedOrders|external=${externalId.orEmpty()}"
        dao.audit(AuditLogEntity(action="ROUTE_EDIT", entityType="Route", entityId=routeId.toString(), oldValue=oldValue, newValue=newValue, source=DataSource.USER_CORRECTION))
    }

    suspend fun deleteRoute(routeId: Long) {
        db.withTransaction {
            val route = dao.route(routeId) ?: error("Трасса #$routeId не найдена")
            if (route.deletedAt != null) return@withTransaction
            val now = System.currentTimeMillis()
            dao.updateRoute(route.copy(deletedAt = now, source = DataSource.USER_CORRECTION))
            dao.softDeleteOrdersForRoute(routeId, now)
            dao.dismissRouteNotifications(routeId)
            dao.audit(AuditLogEntity(action="ROUTE_DELETE_TO_TRASH", entityType="Route", entityId=routeId.toString(), oldValue="active", newValue="deletedAt=$now", source=DataSource.USER_CORRECTION))
        }
        touchData()
    }

    suspend fun deleteCustomerOrder(orderId: Long) {
        db.withTransaction {
            val order=dao.order(orderId) ?: error("Заказ не найден")
            if(order.deletedAt!=null) return@withTransaction
            val now=System.currentTimeMillis()
            dao.softDeleteOrder(orderId,now)
            order.mergeGroupId?.let { gid ->
                if(dao.mergeGroupMemberCount(gid)<2) {
                    dao.clearMergeGroup(gid)
                    dao.mergeGroup(gid)?.let { dao.deleteMergeGroup(it) }
                }
            }
            dao.audit(AuditLogEntity(action="ORDER_DELETE_TO_TRASH",entityType="Order",entityId=orderId.toString(),oldValue="active",newValue="deletedAt=$now",source=DataSource.USER_CORRECTION))
        }
        touchData()
    }

    suspend fun deleteShift(shiftId:Long) {
        db.withTransaction {
            val shift=dao.shift(shiftId) ?: error("Смена не найдена")
            if(shift.deletedAt!=null) return@withTransaction
            val now=System.currentTimeMillis()
            dao.softDeleteShift(shiftId,now)
            dao.softDeleteRoutesForShift(shiftId,now)
            dao.softDeleteOrdersForShift(shiftId,now)
            dao.dismissRouteNotificationsForShift(shiftId)
            dao.audit(AuditLogEntity(action="SHIFT_DELETE_TO_TRASH",entityType="Shift",entityId=shiftId.toString(),oldValue="active",newValue="deletedAt=$now",source=DataSource.USER_CORRECTION))
        }
        touchData()
    }

    suspend fun updateClosedShiftTimes(shiftId:Long, startedAt:Long, endedAt:Long) = db.withTransaction {
        require(endedAt>=startedAt){"Конец смены не может быть раньше начала"}
        val shift=dao.shift(shiftId) ?: error("Смена не найдена")
        require(shift.status!=ShiftStatus.ACTIVE){"Сначала закрой смену"}
        dao.updateShift(shift.copy(startedAt=startedAt,endedAt=endedAt,source=DataSource.USER_CORRECTION))
        dao.audit(AuditLogEntity(action="SHIFT_TIME_EDIT",entityType="Shift",entityId=shiftId.toString(),oldValue="${shift.startedAt}..${shift.endedAt}",newValue="$startedAt..$endedAt",source=DataSource.USER_CORRECTION))
    }

    suspend fun autoFuelEstimate(month:YearMonth): cz.courierledger.fuel.FuelEstimate? {
        val existing=dao.fuel(month.toString())
        if(existing?.source==DataSource.USER_CORRECTION) return null
        val from=month.atDay(1).toString(); val to=month.atEndOfMonth().toString()
        val shifts=dao.observeShifts().first().filter { it.date in from..to }.associateBy { it.date }
        val calendar=dao.calendarBetween(from,to).groupBy { it.date }
        val workDates=(shifts.keys+calendar.keys).distinct().sorted()
        val warehouses=workDates.map { date ->
            shifts[date]?.let { dao.routesForShift(it.id).firstOrNull()?.warehouse }
                ?: calendar[date]?.minByOrNull { it.plannedStartMinutes }?.warehouse
                ?: settings.defaultWarehouse
        }
        return fuelEstimator.estimate(warehouses)
    }

    private suspend fun effectiveMonthlyFuel(month:YearMonth):Long {
        val manual=dao.fuel(month.toString())
        if(manual!=null) return manual.amountHellers
        return autoFuelEstimate(month)?.amountHellers ?: 350_000L
    }

    suspend fun periodStatistics(from: LocalDate?, to: LocalDate?): PeriodStatistics {
        val histories = shiftHistorySummaries().filter { summary ->
            val date = LocalDate.parse(summary.shift.date)
            (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
        }
        val routes = histories.flatMap { it.routes }
        return PeriodStatistics(
            fromDate = from?.toString(),
            toDate = to?.toString(),
            shifts = histories.size,
            factualOrders = histories.sumOf { it.factualOrders },
            clients = histories.sumOf { it.clients },
            rings = histories.sumOf { it.completedRings },
            ot = routes.count { it.route.routeType == RouteType.OT },
            region = routes.count { it.route.routeType == RouteType.REGION },
            express = routes.count { it.route.routeType == RouteType.EXPRESS },
            baseHellers = routes.sumOf { it.baseHellers },
            regionBonusHellers = routes.sumOf { it.regionBonusHellers },
            tipsHellers = histories.sumOf { it.tipsHellers },
            grossHellers = histories.sumOf { it.grossHellers },
            workedMillis = histories.sumOf { it.workedMillis }
        )
    }

    suspend fun saveStatisticsSnapshot(cumulativeOrders: Int, cumulativeTipsHellers: Long?, rawText: String, photoId: Long?): StatisticsSnapshotComparison = db.withTransaction {
        require(cumulativeOrders >= 0) { "Количество заказов не может быть отрицательным" }
        require(cumulativeTipsHellers == null || cumulativeTipsHellers >= 0) { "Чаевые не могут быть отрицательными" }
        val current = StatisticsSnapshotEntity(
            capturedAt = System.currentTimeMillis(),
            cumulativeOrders = cumulativeOrders,
            cumulativeTipsHellers = cumulativeTipsHellers,
            rawText = rawText,
            sourcePhotoId = photoId
        )
        val id = dao.insertSnapshot(current)
        dao.audit(AuditLogEntity(action="STATISTICS_SNAPSHOT_CREATE", entityType="StatisticsSnapshot", entityId=id.toString(), oldValue=null, newValue="orders=$cumulativeOrders;tips=${cumulativeTipsHellers ?: "?"}", source=if (photoId != null) DataSource.OCR else DataSource.MANUAL))
        val latest = dao.latestSnapshots().sortedBy { it.capturedAt }
        if (latest.size < 2) {
            return@withTransaction StatisticsSnapshotComparison(null, latest.lastOrNull(), null, null, null)
        }
        val previous = latest[0]
        val saved = latest[1]
        val delta = saved.cumulativeOrders - previous.cumulativeOrders
        val routes = dao.routesFinishedBetween(previous.capturedAt, saved.capturedAt)
        val routeOrders = routes.sumOf { dao.factualOrderCount(it.id) }
        val routeTips = routes.sumOf { dao.tipsForRoute(it.id) }
        val tipsDelta = previous.cumulativeTipsHellers?.let { before -> saved.cumulativeTipsHellers?.minus(before) }
        StatisticsSnapshotComparison(previous, saved, delta, routeOrders, delta == routeOrders, tipsDelta, if (tipsDelta != null) routeTips else null, tipsDelta?.let { it == routeTips })
    }

    suspend fun latestStatisticsComparison(): StatisticsSnapshotComparison {
        val latest = dao.latestSnapshots().sortedBy { it.capturedAt }
        if (latest.size < 2) return StatisticsSnapshotComparison(null, latest.lastOrNull(), null, null, null)
        val previous = latest[0]
        val current = latest[1]
        val delta = current.cumulativeOrders - previous.cumulativeOrders
        val routes = dao.routesFinishedBetween(previous.capturedAt, current.capturedAt)
        val routeOrders = routes.sumOf { dao.factualOrderCount(it.id) }
        val routeTips = routes.sumOf { dao.tipsForRoute(it.id) }
        val tipsDelta = previous.cumulativeTipsHellers?.let { before -> current.cumulativeTipsHellers?.minus(before) }
        return StatisticsSnapshotComparison(previous, current, delta, routeOrders, delta == routeOrders, tipsDelta, if (tipsDelta != null) routeTips else null, tipsDelta?.let { it == routeTips })
    }

    suspend fun reconciliationForShift(shiftId: Long): ReconciliationResult {
        val shift = dao.shift(shiftId) ?: error("Смена #$shiftId не найдена")
        return ReconciliationEngine(dao).checkShift(shift)
    }

    suspend fun shiftHistorySummaries(): List<ShiftHistorySummary> {
        val shifts = dao.observeShifts().first()
        return shifts.map { shift ->
            val routes = dao.routesForShift(shift.id)
            val routeSummaries = routes.map { route ->
                val raw = dao.rawOrderCount(route.id)
                val factual = dao.factualOrderCount(route.id)
                val tips = dao.tipsForRoute(route.id)
                val groups = dao.activeMergeGroups(route.id).size
                val money = routeEarnings(route)
                RouteHistorySummary(
                    route = route,
                    clients = raw,
                    factualOrders = factual,
                    mergeGroups = groups,
                    tipsHellers = tips,
                    baseHellers = money.base.hellers,
                    regionBonusHellers = money.regionBonus.hellers,
                    grossHellers = money.gross.hellers
                )
            }
            ShiftHistorySummary(
                shift = shift,
                routes = routeSummaries,
                plannedRings = shift.plannedRings,
                completedRings = routes.sumOf { EarningsCalculator.rings(it.routeType) },
                clients = routeSummaries.sumOf { it.clients },
                factualOrders = routeSummaries.sumOf { it.factualOrders },
                tipsHellers = routeSummaries.sumOf { it.tipsHellers },
                grossHellers = routeSummaries.sumOf { it.grossHellers }
            )
        }
    }

    suspend fun mergeExactAddresses(routeId: Long): Int {
        val merged = MergeEngine(db).mergeExactAddresses(routeId)
        refreshLowOrderBonusReminder(routeId)
        return merged
    }

    private suspend fun refreshLowOrderBonusReminder(routeId: Long) = db.withTransaction {
        val route = dao.route(routeId) ?: return@withTransaction
        if (route.deletedAt != null) return@withTransaction
        val raw = dao.rawOrderCount(routeId)
        val factual = dao.factualOrderCount(routeId)
        val qualifies = raw >= 8 && factual <= 4 && factual * 10 <= raw * 4
        if (!qualifies) return@withTransaction
        if (dao.activeNotificationFor("LOW_ORDER_BONUS", "Route", routeId) == null) {
            dao.insertNotification(AppNotificationEntity(
                kind = "LOW_ORDER_BONUS",
                title = "Возможен бонус / компенсация",
                message = "Трасса #$routeId: исходных заказов $raw, фактических $factual. Проверь с координатором; деньги автоматически не начисляются.",
                linkedEntityType = "Route", linkedEntityId = routeId
            ))
        }
    }

    suspend fun dismissNotification(id: Long) = dao.dismissNotification(id)

    suspend fun restoreRouteFromTrash(route: RouteEntity) {
        db.withTransaction {
            val deletedAt=route.deletedAt ?: error("Трасса не в корзине")
            val shift=dao.shift(route.shiftId) ?: error("Смена трассы не найдена")
            require(shift.deletedAt == null) { "Сначала восстановите смену" }
            dao.restoreRoute(route.id)
            dao.restoreOrdersForRoute(route.id, deletedAt)
            dao.audit(AuditLogEntity(action="ROUTE_RESTORE", entityType="Route", entityId=route.id.toString(), oldValue="deletedAt=${route.deletedAt}", newValue="active", source=DataSource.USER_CORRECTION))
        }
        touchData()
    }

    suspend fun restoreCustomerOrderFromTrash(order: OrderEntity) {
        db.withTransaction {
            val deletedAt=order.deletedAt ?: error("Клиент не в корзине")
            require(dao.activeRoute(order.routeId) != null) { "Сначала восстановите трассу и смену" }
            dao.restoreOrder(order.id,deletedAt)
            dao.audit(AuditLogEntity(action="ORDER_RESTORE",entityType="Order",entityId=order.id.toString(),oldValue="deletedAt=$deletedAt",newValue="active",source=DataSource.USER_CORRECTION))
        }
        MergeEngine(db).mergeExactAddresses(order.routeId)
        touchData()
    }

    suspend fun restoreShiftFromTrash(shift:ShiftEntity) {
        db.withTransaction {
            val deletedAt=shift.deletedAt ?: error("Смена не в корзине")
            dao.restoreShift(shift.id,deletedAt)
            dao.restoreRoutesForShift(shift.id,deletedAt)
            dao.restoreOrdersForShift(shift.id,deletedAt)
            dao.audit(AuditLogEntity(action="SHIFT_RESTORE",entityType="Shift",entityId=shift.id.toString(),oldValue="deletedAt=$deletedAt",newValue="active",source=DataSource.USER_CORRECTION))
        }
        touchData()
    }

    suspend fun restoreFinancialFromTrash(entry: FinancialEntryEntity) = db.withTransaction {
        dao.restoreFinancial(entry.id)
        dao.audit(AuditLogEntity(action="FINANCIAL_RESTORE", entityType="FinancialEntry", entityId=entry.id.toString(), oldValue="deletedAt=${entry.deletedAt}", newValue="active", source=DataSource.USER_CORRECTION))
    }

    suspend fun restoreAdvanceFromTrash(entry: AdvanceEntity) = db.withTransaction {
        dao.restoreAdvance(entry.id)
        dao.audit(AuditLogEntity(action="ADVANCE_RESTORE", entityType="Advance", entityId=entry.id.toString(), oldValue="deletedAt=${entry.deletedAt}", newValue="active", source=DataSource.USER_CORRECTION))
    }

    suspend fun permanentlyDeleteRoute(route: RouteEntity) = db.withTransaction {
        require(route.deletedAt != null) { "Сначала отправь трассу в корзину" }
        dao.hardDeleteOrdersForRoute(route.id)
        dao.hardDeleteMergeGroupsForRoute(route.id)
        dao.hardDeleteRoute(route.id)
        dao.audit(AuditLogEntity(action="ROUTE_PURGE", entityType="Route", entityId=route.id.toString(), oldValue="trash", newValue="purged", source=DataSource.USER_CORRECTION))
    }

    suspend fun permanentlyDeleteCustomerOrder(order:OrderEntity)=db.withTransaction {
        require(order.deletedAt!=null)
        val customerId=order.customerId
        dao.hardDeleteOrder(order.id)
        if(dao.orderCountForCustomer(customerId)==0) dao.hardDeleteCustomer(customerId)
        dao.audit(AuditLogEntity(action="ORDER_PURGE",entityType="Order",entityId=order.id.toString(),oldValue="trash",newValue="purged",source=DataSource.USER_CORRECTION))
    }

    suspend fun permanentlyDeleteShift(shift:ShiftEntity)=db.withTransaction {
        require(shift.deletedAt!=null)
        val routes=dao.observeDeletedRoutes().first().filter { it.shiftId==shift.id }
        routes.forEach { r -> dao.hardDeleteOrdersForRoute(r.id); dao.hardDeleteMergeGroupsForRoute(r.id); dao.hardDeleteRoute(r.id) }
        dao.hardDeleteShift(shift.id)
        dao.audit(AuditLogEntity(action="SHIFT_PURGE",entityType="Shift",entityId=shift.id.toString(),oldValue="trash",newValue="purged",source=DataSource.USER_CORRECTION))
    }

    suspend fun permanentlyDeleteFinancial(entry: FinancialEntryEntity) = db.withTransaction {
        require(entry.deletedAt != null)
        dao.hardDeleteFinancial(entry.id)
        dao.audit(AuditLogEntity(action="FINANCIAL_PURGE", entityType="FinancialEntry", entityId=entry.id.toString(), oldValue="trash", newValue="purged", source=DataSource.USER_CORRECTION))
    }

    suspend fun permanentlyDeleteAdvance(entry: AdvanceEntity) = db.withTransaction {
        require(entry.deletedAt != null)
        dao.hardDeleteAdvance(entry.id)
        dao.audit(AuditLogEntity(action="ADVANCE_PURGE", entityType="Advance", entityId=entry.id.toString(), oldValue="trash", newValue="purged", source=DataSource.USER_CORRECTION))
    }

    suspend fun purgeExpiredTrash(now: Long = System.currentTimeMillis()) = db.withTransaction {
        val before = now - 30L * 24L * 60L * 60L * 1000L
        dao.purgeFinancial(before)
        dao.purgeAdvances(before)
        dao.expiredDeletedOrderIds(before).forEach { orderId ->
            val order = dao.order(orderId)
            dao.hardDeleteOrder(orderId)
            order?.customerId?.let { customerId -> if (dao.orderCountForCustomer(customerId) == 0) dao.hardDeleteCustomer(customerId) }
        }
        dao.expiredDeletedRouteIds(before).forEach { routeId ->
            dao.hardDeleteOrdersForRoute(routeId)
            dao.hardDeleteMergeGroupsForRoute(routeId)
            dao.hardDeleteRoute(routeId)
        }
        dao.expiredDeletedShiftIds(before).forEach { shiftId ->
            dao.hardDeleteShift(shiftId)
        }
    }
    suspend fun splitMergeGroup(groupId: Long) = MergeEngine(db).split(groupId)
    suspend fun splitAllMergeGroups(routeId: Long) = MergeEngine(db).splitAll(routeId)

    suspend fun analyticsOverview(from:LocalDate?,to:LocalDate?):AnalyticsOverview {
        val histories=shiftHistorySummaries().filter { h -> val d=LocalDate.parse(h.shift.date); (from==null||!d.isBefore(from))&&(to==null||!d.isAfter(to)) }
        val rows=dao.observeCustomerOrders().first().filter { r -> val d=LocalDate.parse(r.routeDate); (from==null||!d.isBefore(from))&&(to==null||!d.isAfter(to)) }
        val topTipCustomer=rows.maxByOrNull { it.tipHellers }
        val tipDays=rows.groupBy { it.routeDate }.mapValues { e -> e.value.sumOf { it.tipHellers } }
        val tipMonths=rows.groupBy { YearMonth.from(LocalDate.parse(it.routeDate)).toString() }.mapValues { e -> e.value.sumOf { it.tipHellers } }
        return AnalyticsOverview(
            topDay=histories.maxByOrNull{it.grossHellers}, bottomDay=histories.minByOrNull{it.grossHellers},
            longestShift=histories.maxByOrNull{it.workedMillis}, shortestShift=histories.minByOrNull{it.workedMillis},
            mostOrdersDay=histories.maxByOrNull{it.factualOrders}, leastOrdersDay=histories.minByOrNull{it.factualOrders},
            topTipCustomerName=topTipCustomer?.let{listOf(it.firstName,it.lastName).filter(String::isNotBlank).joinToString(" ").ifBlank{"Без имени"}},
            topTipCustomerHellers=topTipCustomer?.tipHellers?:0L,
            topTipDay=tipDays.maxByOrNull{it.value}?.key, topTipDayHellers=tipDays.maxOfOrNull{it.value}?:0L,
            topTipMonth=tipMonths.maxByOrNull{it.value}?.key, topTipMonthHellers=tipMonths.maxOfOrNull{it.value}?:0L
        )
    }

    suspend fun routeEarnings(route: RouteEntity): RouteMoneyResult {
        require(dao.activeRoute(route.id) != null) { "Трасса удалена или относится к удалённой смене" }
        val orders = dao.factualOrderCount(route.id)
        val tips = dao.tipsForRoute(route.id)
        val date = LocalDate.parse(route.routeDate)
        val rate = dao.rateFor(route.routeDate)
        return EarningsCalculator.route(RouteMoneyInput(date,route.routeType,orders,tips,rate?.mondayThursdayHellers ?: 5_000,rate?.fridaySundayHellers ?: 8_000))
    }

    suspend fun periodMoney(from: LocalDate?, to: LocalDate?): PeriodMoneyResult {
        val histories = shiftHistorySummaries().filter { summary ->
            val date = LocalDate.parse(summary.shift.date)
            (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to))
        }
        val routeGross = histories.sumOf { it.grossHellers }
        val effectiveFrom = from ?: histories.minOfOrNull { LocalDate.parse(it.shift.date) }
        val effectiveTo = to ?: histories.maxOfOrNull { LocalDate.parse(it.shift.date) }
        if (effectiveFrom == null || effectiveTo == null) {
            return PeriodMoneyResult(Money(routeGross), Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO)
        }
        val fromStr = effectiveFrom.toString()
        val toStr = effectiveTo.toString()
        val financial = dao.financialBetween(fromStr, toStr)
        val advances = dao.advancesBetween(fromStr, toStr).sumOf { it.amountHellers }
        val bonuses = financial.filter { it.type == FinancialType.BONUS || it.type == FinancialType.COMPENSATION }.sumOf { it.amountHellers }
        val penalties = financial.filter { it.type == FinancialType.PENALTY }.sumOf { kotlin.math.abs(it.amountHellers) }
        var fuel = 0L
        var cursor = YearMonth.from(effectiveFrom)
        val endMonth = YearMonth.from(effectiveTo)
        while (!cursor.isAfter(endMonth)) {
            val monthStart = cursor.atDay(1)
            val monthEnd = cursor.atEndOfMonth()
            val overlapStart = if (effectiveFrom.isAfter(monthStart)) effectiveFrom else monthStart
            val overlapEnd = if (effectiveTo.isBefore(monthEnd)) effectiveTo else monthEnd
            val overlapDays = java.time.temporal.ChronoUnit.DAYS.between(overlapStart, overlapEnd) + 1L
            val monthly = effectiveMonthlyFuel(cursor)
            fuel += (monthly * overlapDays / cursor.lengthOfMonth())
            cursor = cursor.plusMonths(1)
        }
        return PeriodMoneyResult(Money(routeGross), Money(bonuses), Money.ZERO, Money(penalties), Money(fuel), Money(advances))
    }

    suspend fun monthSummary(month: YearMonth): PeriodMoneyResult {
        val from = month.atDay(1).toString()
        val to = month.atEndOfMonth().toString()
        val histories = shiftHistorySummaries().filter { it.shift.date in from..to }
        val routeGross = histories.sumOf { it.grossHellers }
        val financial = dao.financialBetween(from, to)
        val advances = dao.advancesBetween(from, to).sumOf { it.amountHellers }
        val fuel = effectiveMonthlyFuel(month)
        val bonuses = financial.filter { it.type == FinancialType.BONUS || it.type == FinancialType.COMPENSATION }.sumOf { it.amountHellers }
        val comps = 0L
        val penalties = financial.filter { it.type == FinancialType.PENALTY }.sumOf { kotlin.math.abs(it.amountHellers) }
        return PeriodMoneyResult(Money(routeGross), Money(bonuses), Money(comps), Money(penalties), Money(fuel), Money(advances))
    }
}
