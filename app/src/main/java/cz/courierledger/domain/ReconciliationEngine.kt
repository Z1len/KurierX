package cz.courierledger.domain

import cz.courierledger.db.*

data class CheckItem(val ok: Boolean, val title: String, val detail: String)
data class ReconciliationResult(val items: List<CheckItem>) { val passed get() = items.all { it.ok } }

class ReconciliationEngine(private val dao: CourierDao) {
    suspend fun checkShift(shift: ShiftEntity): ReconciliationResult {
        val routes = dao.routesForShift(shift.id)
        val rings = routes.sumOf { EarningsCalculator.rings(it.routeType) }
        val items = mutableListOf(
            CheckItem(
                rings == shift.plannedRings,
                "Колечки",
                "План ${shift.plannedRings}, факт $rings"
            )
        )

        if (routes.isEmpty() && shift.plannedRings > 0) {
            items += CheckItem(false, "Трассы", "В смене нет ни одной закрытой трассы")
        }

        for (route in routes) {
            val raw = dao.rawOrderCount(route.id)
            val factual = dao.factualOrderCount(route.id)
            val reported = route.reportedOrderCount

            items += CheckItem(
                raw > 0 || reported == null || reported == 0,
                "Заказники трассы #${route.id}",
                if (raw > 0) "Распознано клиентов: $raw, фактических заказов: $factual" else "Заказники не обработаны"
            )

            if (reported != null && raw > 0) {
                items += CheckItem(
                    reported == raw,
                    "Количество в трассе #${route.id}",
                    "Сообщение о трассе: $reported, карточек заказников: $raw"
                )
            }
        }

        val snapshots = dao.latestSnapshots().sortedBy { it.capturedAt }
        if (snapshots.size == 2) {
            val delta = snapshots[1].cumulativeOrders - snapshots[0].cumulativeOrders
            val routeOrders = routes.sumOf { dao.factualOrderCount(it.id) }
            items += CheckItem(
                delta == routeOrders,
                "Статистика курьера",
                "Прирост $delta, фактических заказов по трассам $routeOrders"
            )
        }

        return ReconciliationResult(items)
    }
}
