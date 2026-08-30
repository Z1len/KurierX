package cz.courierledger.domain

import cz.courierledger.db.RouteType
import java.time.DayOfWeek
import java.time.LocalDate

data class RouteMoneyInput(val date: LocalDate, val type: RouteType, val factualOrders: Int, val tipsHellers: Long, val weekdayRateHellers: Long = 5_000, val weekendRateHellers: Long = 8_000)
data class RouteMoneyResult(val base: Money, val regionBonus: Money, val tips: Money) { val gross get() = base + regionBonus + tips }
data class PeriodMoneyResult(val routeGross: Money, val bonuses: Money, val compensations: Money, val penalties: Money, val diesel: Money, val advances: Money) {
    val accrued get() = routeGross + bonuses + compensations - penalties
    val net get() = accrued - diesel
    val expectedPayout get() = net - advances
}

object EarningsCalculator {
    fun route(input: RouteMoneyInput): RouteMoneyResult {
        require(input.factualOrders >= 0)
        val highRate = input.date.dayOfWeek in setOf(DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        val rate = if (highRate) input.weekendRateHellers else input.weekdayRateHellers
        return RouteMoneyResult(Money(rate * input.factualOrders), Money(if (input.type == RouteType.REGION) 25_000 else 0), Money(input.tipsHellers))
    }
    fun rings(type: RouteType): Int = if (type == RouteType.REGION) 2 else 1
}
