package cz.courierledger.domain

import cz.courierledger.db.RouteType
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class DomainTests {
    @Test fun regionCountsTwoRings() = assertEquals(2, EarningsCalculator.rings(RouteType.REGION))
    @Test fun fridayRateAndRegionBonusAreCorrect() {
        val r = EarningsCalculator.route(RouteMoneyInput(LocalDate.of(2026,8,28), RouteType.REGION, 10, 15_000))
        assertEquals(80_000, r.base.hellers)
        assertEquals(25_000, r.regionBonus.hellers)
        assertEquals(120_000, r.gross.hellers)
    }
    @Test fun sameAddressDifferentOrderNormalizesEqually() {
        assertEquals(AddressNormalizer.normalize("Praha 5, Plzeňská 123"), AddressNormalizer.normalize("Plzeňská 123, Praha 5"))
    }
}
