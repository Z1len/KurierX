package cz.courierledger.ocr

import cz.courierledger.db.Warehouse
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

data class CalendarImportEntry(
    val date: LocalDate,
    val plannedStartMinutes: Int,
    val plannedRings: Int,
    val warehouse: Warehouse,
    val confidence: Double
)

data class CalendarImportResult(
    val month: YearMonth?,
    val entries: List<CalendarImportEntry>,
    val confidence: Double,
    val warnings: List<String>
)

object CalendarOcrParser {
    private val monthNames = mapOf(
        "leden" to 1,
        "unor" to 2,
        "brezen" to 3,
        "duben" to 4,
        "kveten" to 5,
        "cerven" to 6,
        "cervenec" to 7,
        "srpen" to 8,
        "zari" to 9,
        "rijen" to 10,
        "listopad" to 11,
        "prosinec" to 12
    )

    private val compactSchedule = Regex("(?i)(?:(CH|HP|L)\\s*)?(\\d{1,2})\\s*[:.]\\s*(\\d{2})\\s*[-–—]?\\s*(\\d{1,2})\\s*[kK]")
    private val timeRegex = Regex("(?i)(CH|HP|L)?\\s*(\\d{1,2})\\s*[:.]\\s*(\\d{2})")
    private val ringsRegex = Regex("(?i)(\\d{1,2})\\s*[kK]")

    fun parse(ocr: OcrText): CalendarImportResult {
        val monthHit = findMonth(ocr)
        val month = monthHit?.first
        if (month == null) {
            return CalendarImportResult(null, emptyList(), 0.0, listOf("Не удалось определить месяц и год на скриншоте"))
        }

        val monthLineBottom = monthHit.second?.bottom ?: 0
        val dayLines = ocr.lines.filter { line ->
            line.top > monthLineBottom && line.text.trim().toIntOrNull()?.let { it in 1..month.lengthOfMonth() } == true
        }
        if (dayLines.isEmpty()) {
            return CalendarImportResult(month, emptyList(), .25, listOf("Месяц найден, но номера дней не распознаны"))
        }

        val firstDow = month.atDay(1).dayOfWeek.isoIndex()
        val detected = dayLines.mapNotNull { line ->
            val day = line.text.trim().toIntOrNull() ?: return@mapNotNull null
            if (day !in 1..month.lengthOfMonth()) return@mapNotNull null
            val cellIndex = firstDow + day - 1
            val col = cellIndex % 7
            val row = cellIndex / 7
            DetectedDay(day, col, row, line)
        }

        val xByCol = (0..6).associateWith { col -> detected.filter { it.col == col }.map { it.line.centerX() }.averageOrNull() }
        val yByRow = (0..5).associateWith { row -> detected.filter { it.row == row }.map { it.line.centerY.toDouble() }.averageOrNull() }
        val xStep = medianPositiveDiff(xByCol.values.filterNotNull().sorted()).takeIf { it > 10 } ?: inferXStep(ocr)
        val yStep = medianPositiveDiff(yByRow.values.filterNotNull().sorted()).takeIf { it > 15 } ?: inferYStep(detected)
        val xOrigin = estimateOrigin(xByCol, xStep)
        val yOrigin = estimateOrigin(yByRow, yStep)

        val parsed = mutableListOf<CalendarImportEntry>()
        for (day in 1..month.lengthOfMonth()) {
            val cellIndex = firstDow + day - 1
            val col = cellIndex % 7
            val row = cellIndex / 7
            val knownDay = detected.firstOrNull { it.day == day }
            val centerX = knownDay?.line?.centerX()?.toDouble() ?: (xOrigin + col * xStep)
            val dayY = knownDay?.line?.centerY?.toDouble() ?: (yOrigin + row * yStep)
            val left = centerX - xStep * .54
            val right = centerX + xStep * .54
            val top = (knownDay?.line?.bottom?.toDouble() ?: dayY) - 2
            val bottom = dayY + yStep * .83

            val cellLines = ocr.lines
                .filter { it.centerX() in left..right && it.centerY.toDouble() in top..bottom }
                .filterNot { it.text.trim() == day.toString() }
                .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
            if (cellLines.isEmpty()) continue

            val cellText = cellLines.joinToString(" ") { cleanup(it.text) }
            val schedules = parseSchedules(cellText)
            schedules.forEach { schedule ->
                parsed += CalendarImportEntry(
                    date = month.atDay(day),
                    plannedStartMinutes = schedule.hour * 60 + schedule.minute,
                    plannedRings = schedule.rings,
                    warehouse = schedule.warehouse,
                    confidence = ((ocr.confidence * .72) + (if (knownDay != null) .12 else .03) + (if (schedule.exact) .10 else .04)).coerceIn(0.0, 1.0)
                )
            }
        }

        val unique = parsed.distinctBy { Triple(it.date, it.plannedStartMinutes, it.warehouse) }
            .sortedWith(compareBy<CalendarImportEntry> { it.date }.thenBy { it.plannedStartMinutes })
        val warnings = buildList {
            if (unique.isEmpty()) add("Не удалось найти рабочие блоки вида 6:00 · 4K")
            val suspicious = unique.count { it.plannedRings !in 1..8 }
            if (suspicious > 0) add("$suspicious блок(ов) имеют необычное количество колечек — проверь перед сохранением")
            if (dayLines.size < month.lengthOfMonth() / 2) add("OCR увидел мало номеров дней; внимательно проверь пропуски")
        }
        val confidence = if (unique.isEmpty()) .3 else unique.map { it.confidence }.average().coerceIn(0.0, 1.0)
        return CalendarImportResult(month, unique, confidence, warnings)
    }

    private fun findMonth(ocr: OcrText): Pair<YearMonth, OcrLine?>? {
        val yearRegex = Regex("\\b(20\\d{2})\\b")
        for (line in ocr.lines) {
            val normalized = ascii(line.text).lowercase()
            val month = monthNames.entries.firstOrNull { normalized.contains(it.key) }?.value ?: continue
            val year = yearRegex.find(normalized)?.groupValues?.get(1)?.toIntOrNull()
                ?: yearRegex.find(ascii(ocr.text))?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            return YearMonth.of(year, month) to line
        }
        val normalized = ascii(ocr.text).lowercase()
        val month = monthNames.entries.firstOrNull { normalized.contains(it.key) }?.value ?: return null
        val year = yearRegex.find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return YearMonth.of(year, month) to null
    }

    private data class Schedule(val hour: Int, val minute: Int, val rings: Int, val warehouse: Warehouse, val exact: Boolean)

    private fun parseSchedules(text: String): List<Schedule> {
        val compact = compactSchedule.findAll(text).mapNotNull { match ->
            val hour = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val minute = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            val rings = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
            if (hour !in 0..23 || minute !in 0..59 || rings !in 1..20) return@mapNotNull null
            Schedule(hour, minute, rings, warehouse(match.groupValues[1]), true)
        }.toList()
        if (compact.isNotEmpty()) return compact

        val times = timeRegex.findAll(text).mapNotNull { match ->
            val hour = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            val minute = match.groupValues[3].toIntOrNull() ?: return@mapNotNull null
            if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
            Triple(hour, minute, warehouse(match.groupValues[1]))
        }.toList()
        val rings = ringsRegex.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull()?.takeIf { n -> n in 1..20 } }.toList()
        return times.zip(rings).map { (time, ring) -> Schedule(time.first, time.second, ring, time.third, false) }
    }

    private fun warehouse(code: String): Warehouse = when (code.trim().uppercase()) {
        "CH" -> Warehouse.CHRASTANY
        "HP" -> Warehouse.HORNI_POCERNICE
        else -> Warehouse.LIBOC
    }

    private fun cleanup(text: String): String = text
        .replace('■', ' ')
        .replace('▪', ' ')
        .replace('□', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun ascii(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    private fun DayOfWeek.isoIndex(): Int = value - 1
    private fun OcrLine.centerX(): Double = (left + right) / 2.0

    private data class DetectedDay(val day: Int, val col: Int, val row: Int, val line: OcrLine)

    private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()

    private fun medianPositiveDiff(values: List<Double>): Double {
        val diffs = values.zipWithNext().map { it.second - it.first }.filter { it > 2 }.sorted()
        return if (diffs.isEmpty()) 0.0 else diffs[diffs.size / 2]
    }

    private fun inferXStep(ocr: OcrText): Double {
        val max = ocr.lines.maxOfOrNull { it.right }?.toDouble() ?: 700.0
        val min = ocr.lines.minOfOrNull { it.left }?.toDouble() ?: 0.0
        return ((max - min) / 7.0).coerceAtLeast(55.0)
    }

    private fun inferYStep(days: List<DetectedDay>): Double {
        val rows = days.groupBy { it.row }.values.map { group -> group.map { it.line.centerY.toDouble() }.average() }.sorted()
        return medianPositiveDiff(rows).takeIf { it > 20 } ?: 120.0
    }

    private fun estimateOrigin(values: Map<Int, Double?>, step: Double): Double {
        val estimates = values.mapNotNull { (index, value) -> value?.minus(index * step) }
        return estimates.averageOrNull() ?: 0.0
    }
}
