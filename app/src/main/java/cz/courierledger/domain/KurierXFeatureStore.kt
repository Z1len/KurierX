package cz.courierledger.domain

import android.content.Context
import android.content.SharedPreferences
import cz.courierledger.db.FinancialType
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max

/**
 * Central persistence + calculation layer for KurierX 2.4 additions.
 *
 * The class intentionally uses the existing app SharedPreferences instead of changing Room tables.
 * This lets the first upgrade be installed over the current app without touching or recreating the
 * encrypted Room database. Existing shifts, routes, orders, OCR data and statistics stay intact.
 *
 * Values that belong to a specific existing entity are keyed by its stable Room id (shiftId/routeId).
 */
class KurierXFeatureStore(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------
    // 1. Statistics order baseline
    // ---------------------------------------------------------------------

    /** First imported cumulative order value. Null means that no baseline exists yet. */
    var orderBaseline: Int?
        get() = if (prefs.contains(KEY_ORDER_BASELINE)) prefs.getInt(KEY_ORDER_BASELINE, 0) else null
        private set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_ORDER_BASELINE)
                else putInt(KEY_ORDER_BASELINE, value.coerceAtLeast(0))
            }.apply()
        }

    /**
     * Called after OCR confirmation, not directly after recognition.
     * First confirmed screenshot only establishes the starting point and returns 0 new orders.
     */
    fun acceptCumulativeOrders(cumulativeOrders: Int): OrderBaselineResult {
        require(cumulativeOrders >= 0) { "Количество заказов не может быть отрицательным" }
        val baseline = orderBaseline
        if (baseline == null) {
            orderBaseline = cumulativeOrders
            return OrderBaselineResult(
                baseline = cumulativeOrders,
                cumulative = cumulativeOrders,
                newOrders = 0,
                baselineCreated = true
            )
        }
        return OrderBaselineResult(
            baseline = baseline,
            cumulative = cumulativeOrders,
            newOrders = max(0, cumulativeOrders - baseline),
            baselineCreated = false
        )
    }

    /** Developer Mode only. */
    fun developerSetOrderBaseline(value: Int?) {
        require(value == null || value >= 0) { "Точка отсчёта не может быть отрицательной" }
        orderBaseline = value
    }

    // ---------------------------------------------------------------------
    // 2. Per-route mileage
    // ---------------------------------------------------------------------

    fun routeKm(routeId: Long): Double? = readDoubleMap(KEY_ROUTE_KM)[routeId.toString()]

    fun setRouteKm(routeId: Long, km: Double) {
        require(routeId > 0) { "Некорректный ID трассы" }
        require(km >= 0.0 && km <= MAX_REASONABLE_KM) { "Некорректный километраж трассы" }
        val map = readDoubleMap(KEY_ROUTE_KM).toMutableMap()
        map[routeId.toString()] = km
        writeDoubleMap(KEY_ROUTE_KM, map)
    }

    /** Developer Mode only. */
    fun developerRemoveRouteKm(routeId: Long) {
        val map = readDoubleMap(KEY_ROUTE_KM).toMutableMap()
        map.remove(routeId.toString())
        writeDoubleMap(KEY_ROUTE_KM, map)
    }

    fun routeMileageSnapshot(): Map<Long, Double> = readDoubleMap(KEY_ROUTE_KM)
        .mapNotNull { (key, value) -> key.toLongOrNull()?.let { it to value } }
        .toMap()

    // ---------------------------------------------------------------------
    // 3. Odometer readings per shift
    // ---------------------------------------------------------------------

    fun odometer(shiftId: Long): ShiftOdometer = readOdometers()[shiftId.toString()] ?: ShiftOdometer()

    /** May be entered before joining the queue. */
    fun setMorningOdometer(shiftId: Long, km: Double?) = updateOdometer(shiftId) {
        it.copy(morningKm = validateOdometer(km))
    }

    /** Must be requested when the user joins the queue. */
    fun setQueueOdometer(shiftId: Long, km: Double?) = updateOdometer(shiftId) {
        it.copy(queueKm = validateOdometer(km))
    }

    /** Must be requested when the shift is closed. */
    fun setClosingOdometer(shiftId: Long, km: Double?) = updateOdometer(shiftId) {
        it.copy(closingKm = validateOdometer(km))
    }

    /** Developer Mode only. */
    fun developerReplaceOdometer(shiftId: Long, value: ShiftOdometer) {
        val morning = validateOdometer(value.morningKm)
        val queue = validateOdometer(value.queueKm)
        val closing = validateOdometer(value.closingKm)
        writeOdometer(shiftId, ShiftOdometer(morning, queue, closing))
    }

    /**
     * Breakdown does not count the known home -> work leg as off-route overrun.
     *
     * If a morning reading is present:
     *   homeToWorkActual = queue - morning
     *   totalActual      = closing - morning
     *
     * Otherwise we can still calculate the post-queue mileage:
     *   totalActual      = closing - queue
     * and homeToWorkActual remains unknown.
     *
     * expectedHomeRoundTripKm is the route-planner estimate home -> warehouse -> home.
     * We subtract it only from a full-day reading that starts at home. This prevents the commute
     * from being classified as a route overrun.
     */
    fun mileageBreakdown(
        shiftId: Long,
        routeIds: Collection<Long>,
        expectedHomeRoundTripKm: Double? = null
    ): MileageBreakdown {
        val odo = odometer(shiftId)
        val routeKm = routeIds.sumOf { routeKm(it) ?: 0.0 }

        val morning = odo.morningKm
        val queue = odo.queueKm
        val closing = odo.closingKm

        val homeToWorkActual = if (morning != null && queue != null && queue >= morning) queue - morning else null
        val totalActual = when {
            morning != null && closing != null && closing >= morning -> closing - morning
            queue != null && closing != null && closing >= queue -> closing - queue
            else -> null
        }

        // If the day starts at home, reserve the complete known commute (home -> work -> home).
        // If only queue -> close is known, reserve at most the planned work -> home half.
        val commuteReserved = totalActual?.let { total ->
            val planned = when {
                morning != null -> expectedHomeRoundTripKm?.coerceAtLeast(0.0) ?: homeToWorkActual?.times(2.0)
                else -> expectedHomeRoundTripKm?.coerceAtLeast(0.0)?.div(2.0)
            }
            planned?.coerceAtMost(total)
        }

        val outsideRoutes = totalActual?.let { total ->
            (total - routeKm - (commuteReserved ?: 0.0)).coerceAtLeast(0.0)
        }

        return MileageBreakdown(
            morningOdometerKm = morning,
            queueOdometerKm = queue,
            closingOdometerKm = closing,
            homeToWorkActualKm = homeToWorkActual,
            homeWorkHomeKm = commuteReserved,
            routeKm = routeKm,
            outsideRouteKm = outsideRoutes,
            totalActualKm = totalActual
        )
    }

    // ---------------------------------------------------------------------
    // 7. Diesel calculation
    // ---------------------------------------------------------------------

    fun dieselCalculation(
        breakdown: MileageBreakdown,
        consumptionLPer100Km: Double,
        dieselPriceKc: Double
    ): DieselCalculation? {
        val total = breakdown.totalActualKm ?: return null
        require(consumptionLPer100Km > 0.0) { "Расход автомобиля должен быть больше 0" }
        require(dieselPriceKc >= 0.0) { "Цена дизеля не может быть отрицательной" }
        val liters = total * consumptionLPer100Km / 100.0
        return DieselCalculation(
            breakdown = breakdown,
            consumptionLPer100Km = consumptionLPer100Km,
            dieselPriceKc = dieselPriceKc,
            liters = liters,
            costKc = liters * dieselPriceKc
        )
    }

    // ---------------------------------------------------------------------
    // 8. Weekday/weekend bonus rates
    // ---------------------------------------------------------------------

    var regionWeekdayHellers: Long
        get() = prefs.getLong(KEY_REGION_WEEKDAY, 0L)
        set(value) = prefs.edit().putLong(KEY_REGION_WEEKDAY, value.coerceAtLeast(0)).apply()

    var onTimeWeekdayHellers: Long
        get() = prefs.getLong(KEY_ONTIME_WEEKDAY, 0L)
        set(value) = prefs.edit().putLong(KEY_ONTIME_WEEKDAY, value.coerceAtLeast(0)).apply()

    var regionWeekendHellers: Long
        get() = prefs.getLong(KEY_REGION_WEEKEND, 0L)
        set(value) = prefs.edit().putLong(KEY_REGION_WEEKEND, value.coerceAtLeast(0)).apply()

    var onTimeWeekendHellers: Long
        get() = prefs.getLong(KEY_ONTIME_WEEKEND, 0L)
        set(value) = prefs.edit().putLong(KEY_ONTIME_WEEKEND, value.coerceAtLeast(0)).apply()

    fun ratesFor(date: LocalDate): BonusRates {
        val weekend = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
        return if (weekend) {
            BonusRates(regionWeekendHellers, onTimeWeekendHellers, true)
        } else {
            BonusRates(regionWeekdayHellers, onTimeWeekdayHellers, false)
        }
    }

    // ---------------------------------------------------------------------
    // 11. First-run tutorial
    // ---------------------------------------------------------------------

    var tutorialCompleted: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_COMPLETED, value).apply()

    fun resetTutorial() {
        tutorialCompleted = false
    }

    // Morning odometer can be entered before a ShiftEntity exists.
    fun pendingMorningOdometer(date: LocalDate): Double? =
        prefs.getString("pending_morning_${date}", null)?.toDoubleOrNull()

    fun setPendingMorningOdometer(date: LocalDate, km: Double?) {
        val checked = validateOdometer(km)
        prefs.edit().apply {
            if (checked == null) remove("pending_morning_${date}")
            else putString("pending_morning_${date}", checked.toString())
        }.apply()
    }

    fun consumePendingMorningOdometer(date: LocalDate): Double? {
        val value = pendingMorningOdometer(date)
        prefs.edit().remove("pending_morning_${date}").apply()
        return value
    }

    // GitHub updater is intentionally configurable in Developer Mode because the release
    // repository can be moved without requiring a new APK just to change the URL.
    var githubReleaseApiUrl: String
        get() = prefs.getString(KEY_GITHUB_RELEASE_API, DEFAULT_GITHUB_RELEASE_API) ?: DEFAULT_GITHUB_RELEASE_API
        set(value) = prefs.edit().putString(KEY_GITHUB_RELEASE_API, value.trim()).apply()

    // ---------------------------------------------------------------------
    // 4/5/6. OCR helpers
    // ---------------------------------------------------------------------

    /**
     * KurierX-specific correction requested in the specification.
     * "Hodnota nákupu není skan" is always treated as a deduction/penalty.
     */
    fun correctedFinancialType(description: String, recognized: FinancialType): FinancialType {
        val normalized = description
            .lowercase()
            .replace('á', 'a')
            .replace('ě', 'e')
            .replace('í', 'i')
            .replace('ý', 'y')
            .replace('ů', 'u')
            .replace('ú', 'u')
            .replace('ř', 'r')
            .replace('č', 'c')
            .replace('š', 's')
            .replace('ž', 'z')
            .replace('ň', 'n')
            .replace('ť', 't')
            .replace('ď', 'd')
        return if ("hodnota nakupu neni skan" in normalized) FinancialType.PENALTY else recognized
    }

    /** Detects suspicious duplicates but never removes them automatically. */
    fun findFinancialDuplicates(rows: List<FinancialDraftFingerprint>): List<DuplicateGroup> = rows
        .groupBy { Triple(it.amountHellers, it.type, normalizeDescription(it.description)) }
        .filterValues { it.size > 1 }
        .map { (key, values) ->
            DuplicateGroup(
                amountHellers = key.first,
                type = key.second,
                normalizedDescription = key.third,
                count = values.size,
                originalIndexes = values.map { it.index }
            )
        }

    private fun normalizeDescription(value: String): String = value
        .trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")

    private fun validateOdometer(value: Double?): Double? {
        if (value == null) return null
        require(value >= 0.0 && value <= MAX_ODOMETER_KM) { "Некорректное значение спидометра" }
        return value
    }

    private fun updateOdometer(shiftId: Long, block: (ShiftOdometer) -> ShiftOdometer) {
        require(shiftId > 0) { "Некорректный ID смены" }
        writeOdometer(shiftId, block(odometer(shiftId)))
    }

    private fun writeOdometer(shiftId: Long, value: ShiftOdometer) {
        val all = readOdometers().toMutableMap()
        all[shiftId.toString()] = value
        val root = JSONObject()
        all.forEach { (id, reading) ->
            root.put(id, JSONObject().apply {
                reading.morningKm?.let { put("morning", it) }
                reading.queueKm?.let { put("queue", it) }
                reading.closingKm?.let { put("closing", it) }
            })
        }
        prefs.edit().putString(KEY_ODOMETERS, root.toString()).apply()
    }

    private fun readOdometers(): Map<String, ShiftOdometer> {
        val raw = prefs.getString(KEY_ODOMETERS, null) ?: return emptyMap()
        return runCatching {
            val root = JSONObject(raw)
            root.keys().asSequence().associateWith { id ->
                val obj = root.getJSONObject(id)
                ShiftOdometer(
                    morningKm = if (obj.has("morning")) obj.getDouble("morning") else null,
                    queueKm = if (obj.has("queue")) obj.getDouble("queue") else null,
                    closingKm = if (obj.has("closing")) obj.getDouble("closing") else null
                )
            }
        }.getOrDefault(emptyMap())
    }

    private fun readDoubleMap(key: String): Map<String, Double> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            obj.keys().asSequence().associateWith { obj.getDouble(it) }
        }.getOrDefault(emptyMap())
    }

    private fun writeDoubleMap(key: String, values: Map<String, Double>) {
        val obj = JSONObject()
        values.forEach { (id, value) -> obj.put(id, value) }
        prefs.edit().putString(key, obj.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "kurierx_v24_features"
        private const val KEY_ORDER_BASELINE = "order_baseline"
        private const val KEY_ROUTE_KM = "route_km"
        private const val KEY_ODOMETERS = "shift_odometers"
        private const val KEY_REGION_WEEKDAY = "rate_region_weekday"
        private const val KEY_ONTIME_WEEKDAY = "rate_ontime_weekday"
        private const val KEY_REGION_WEEKEND = "rate_region_weekend"
        private const val KEY_ONTIME_WEEKEND = "rate_ontime_weekend"
        private const val KEY_TUTORIAL_COMPLETED = "tutorial_completed"
        private const val KEY_GITHUB_RELEASE_API = "github_release_api"
        private const val DEFAULT_GITHUB_RELEASE_API = "https://api.github.com/repos/Z1len/KurierX/releases/latest"
        private const val MAX_REASONABLE_KM = 5_000.0
        private const val MAX_ODOMETER_KM = 9_999_999.0
    }
}

data class OrderBaselineResult(
    val baseline: Int,
    val cumulative: Int,
    val newOrders: Int,
    val baselineCreated: Boolean
)

data class ShiftOdometer(
    val morningKm: Double? = null,
    val queueKm: Double? = null,
    val closingKm: Double? = null
)

data class MileageBreakdown(
    val morningOdometerKm: Double?,
    val queueOdometerKm: Double?,
    val closingOdometerKm: Double?,
    val homeToWorkActualKm: Double?,
    val homeWorkHomeKm: Double?,
    val routeKm: Double,
    val outsideRouteKm: Double?,
    val totalActualKm: Double?
)

data class DieselCalculation(
    val breakdown: MileageBreakdown,
    val consumptionLPer100Km: Double,
    val dieselPriceKc: Double,
    val liters: Double,
    val costKc: Double
)

data class BonusRates(
    val regionHellers: Long,
    val onTimeHellers: Long,
    val weekend: Boolean
)

data class FinancialDraftFingerprint(
    val index: Int,
    val amountHellers: Long,
    val type: FinancialType,
    val description: String
)

data class DuplicateGroup(
    val amountHellers: Long,
    val type: FinancialType,
    val normalizedDescription: String,
    val count: Int,
    val originalIndexes: List<Int>
)
