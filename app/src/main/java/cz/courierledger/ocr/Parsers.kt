package cz.courierledger.ocr

import cz.courierledger.db.FinancialType
import cz.courierledger.db.Warehouse
import cz.courierledger.domain.AddressNormalizer
import cz.courierledger.ruian.RuianStreetIndex
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer

data class RouteParse(val orders: Int?, val date: String?, val time: String?, val warehouse: Warehouse?, val routeId: String?, val confidence: Double)
data class CustomerParse(val firstName: String, val lastName: String, val address: String, val normalizedAddress: String, val tipHellers: Long, val packages: Int, val confidence: Double)
data class StatisticsParse(
    val cumulativeOrders: Int?,
    val cumulativeTipsHellers: Long?,
    val confidence: Double
)
data class FinancialParse(val type: FinancialType?, val amountHellers: Long?, val date: String?, val description: String, val confidence: Double)
data class FinancialRowParse(val type: FinancialType?, val amountHellers: Long?, val date: String?, val description: String, val confidence: Double)

object OcrParsers {
    private val dateRegex = Regex("\\b(\\d{1,2})[./-](\\d{1,2})(?:[./-](\\d{2,4}))?\\b")
    private val timeRegex = Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b")
    private val financialDateRegex = Regex("\\b(\\d{1,2})[./](\\d{1,2})[./](\\d{4})\\b")
    private val moneyRegex = Regex("(-?\\d[\\d .]*(?:[,.]\\d{1,2})?)\\s*(?:K[cč]|CZK)", RegexOption.IGNORE_CASE)
    private val tableAmountPrefixRegex = Regex("^([+-]?\\d(?:[\\d \\u00A0.'’]*\\d)?(?:[,.]\\d{1,2})?)\\s*(?:K\\s*[cčć¢eé6]|CZK)?(?:\\b|$)", RegexOption.IGNORE_CASE)
    private val postCodeRegex = Regex("\\b\\d{3}\\s?\\d{2}\\b")
    private val streetNumberRegex = Regex("\\b\\d{1,5}(?:/\\d{1,5})?\\b")

    fun route(text: String, baseConfidence: Double): RouteParse {
        val lower = text.lowercase()
        val orders = Regex("(?:objedn[aá]v(?:ek|ky)|orders?)\\D{0,12}(\\d{1,3})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\\b(\\d{1,3})\\s*(?:objedn[aá]vek|orders?)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
        val wh = when {
            "chrášť" in lower || "chrast" in lower -> Warehouse.CHRASTANY
            "počern" in lower || "pocern" in lower -> Warehouse.HORNI_POCERNICE
            "liboc" in lower || "libeň" in lower || "liben" in lower -> Warehouse.LIBOC
            else -> null
        }
        val id = Regex("(?:trasa|route|tour|id)\\s*[:#]?\\s*([A-Z0-9_-]{3,})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        val confidence = baseConfidence * listOf(orders, dateRegex.find(text), timeRegex.find(text)).count { it != null } / 3.0
        return RouteParse(orders, dateRegex.find(text)?.value, timeRegex.find(text)?.value, wh, id, confidence.coerceIn(0.0, 1.0))
    }

    fun statistics(text: String, baseConfidence: Double): StatisticsParse {
        val orders = Regex("(?:objedn[aá]v(?:ky|ek)|orders?)\\D{0,20}(\\d{1,7})", RegexOption.IGNORE_CASE)
            .findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
        val tips = Regex("(?:d[ýy]ško|dysko|spropitn[ée]|tips?)\\D{0,24}([0-9][0-9 .\u00A0]*(?:[,.][0-9]{1,2})?)\\s*(?:K[cč]|CZK)?", RegexOption.IGNORE_CASE)
            .findAll(text).mapNotNull { moneyToHellers(it.groupValues[1]) }.maxOrNull()
        val hits = listOf(orders, tips).count { it != null }
        val confidence = when (hits) { 2 -> baseConfidence; 1 -> baseConfidence * .72; else -> baseConfidence * .30 }
        return StatisticsParse(orders, tips, confidence.coerceIn(0.0, 1.0))
    }

    fun statistics(result: OcrText): StatisticsParse {
        val lines = result.lines.map { it.copy(text = cleanLine(it.text)) }.filter { it.text.isNotBlank() }
        val elements = result.elements.map { it.copy(text = cleanLine(it.text)) }.filter { it.text.isNotBlank() }
        val geometry = (lines + elements).distinctBy { listOf(it.text, it.left.toString(), it.top.toString(), it.right.toString(), it.bottom.toString()) }
        if (geometry.isEmpty()) return statistics(result.text, result.confidence)

        // Rohlík dashboard puts the VALUE ABOVE its label. Prefer the total-orders tile
        // ("Objednávky celkem"), never a generic "Objednávky" anchor that can drift to Karma.
        val orderAnchor = lines.firstOrNull {
            normalizeOcrLabel(it.text).let { n -> "objednav" in n && ("celkem" in n || "celk" in n || "total" in n) }
        } ?: lines.firstOrNull {
            normalizeOcrLabel(it.text).let { n -> "objednav" in n && "tentomesic" in n }
        } ?: geometry.firstOrNull {
            normalizeOcrLabel(it.text).let { n -> "objednavkycelkem" in n || "orderstotal" in n }
        }
        val tipAnchor = lines.firstOrNull {
            normalizeOcrLabel(it.text).let { n -> "spropit" in n || "dysko" in n || n == "tips" || n == "tip" }
        } ?: geometry.firstOrNull {
            normalizeOcrLabel(it.text).let { n -> "spropit" in n || "dysko" in n || n == "tips" || n == "tip" }
        }

        val fallback = statistics(result.text, result.confidence)
        val orders = orderAnchor?.let { statisticValueAbove(geometry, it, false)?.toIntOrNull() }
            ?: fallback.cumulativeOrders
        val tips = tipAnchor?.let { statisticValueAbove(geometry, it, true)?.let(::moneyToHellers) }
            ?: fallback.cumulativeTipsHellers
        val hits = listOf(orders, tips).count { it != null }
        return StatisticsParse(orders, tips, (result.confidence * when (hits) { 2 -> 1.0; 1 -> .72; else -> .30 }).coerceIn(0.0, 1.0))
    }

    private fun statisticValueAbove(lines: List<OcrLine>, anchor: OcrLine, money: Boolean): String? {
        fun numericCandidate(text: String): String? {
            val cleaned = cleanLine(text)
            if (timeRegex.containsMatchIn(cleaned) || financialDateRegex.containsMatchIn(cleaned)) return null
            val pattern = if (money) {
                Regex("^[^0-9+-]*([+-]?[0-9][0-9 .\u00A0]*(?:[,.][0-9]{1,2})?)(?:\\s*(?:K[cč]|CZK))?[^0-9]*$", RegexOption.IGNORE_CASE)
            } else {
                Regex("^[^0-9]*([0-9]{1,7})[^0-9]*$")
            }
            return pattern.find(cleaned)?.groupValues?.get(1)?.trim()
        }

        // Main dashboard layout: number sits immediately above the label in the same tile.
        lines.asSequence()
            .filter { it !== anchor }
            .filter { it.bottom <= anchor.top + maxOf(4, anchor.height / 3) }
            .filter { anchor.top - it.bottom <= maxOf(anchor.height * 5, 90) }
            .filter {
                val horizontalOverlap = minOf(it.right, anchor.right) - maxOf(it.left, anchor.left)
                horizontalOverlap > 0 || kotlin.math.abs(it.centerX - anchor.centerX) <= maxOf(anchor.height * 4, (anchor.right - anchor.left) / 2)
            }
            .sortedWith(compareBy<OcrLine> { anchor.top - it.bottom }.thenBy { kotlin.math.abs(it.centerX - anchor.centerX) })
            .mapNotNull { numericCandidate(it.text) }
            .firstOrNull()?.let { return it }

        // Compatibility fallback for other versions where the value is beside/below the label.
        return nearestStatisticValue(lines, anchor, money)
    }

    private fun nearestStatisticValue(lines: List<OcrLine>, anchor: OcrLine, money: Boolean): String? {
        fun numericCandidate(text: String): String? {
            val cleaned = cleanLine(text)
            if (timeRegex.containsMatchIn(cleaned) || financialDateRegex.containsMatchIn(cleaned)) return null
            val pattern = if (money) Regex("^[^0-9+-]*([+-]?[0-9][0-9 .\u00A0]*(?:[,.][0-9]{1,2})?)(?:\\s*(?:K[cč]|CZK))?[^0-9]*$", RegexOption.IGNORE_CASE)
            else Regex("^[^0-9]*([0-9]{1,7})[^0-9]*$")
            return pattern.find(cleaned)?.groupValues?.get(1)?.trim()
        }
        lines.asSequence()
            .filter { it !== anchor && kotlin.math.abs(it.centerY - anchor.centerY) <= maxOf(anchor.height, it.height) }
            .filter { it.centerX >= anchor.centerX - anchor.height }
            .sortedBy { kotlin.math.abs(it.left - anchor.right) }
            .mapNotNull { numericCandidate(it.text) }.firstOrNull()?.let { return it }
        return lines.asSequence()
            .filter { it !== anchor && it.top >= anchor.bottom - 4 && it.top - anchor.bottom <= anchor.height * 4 }
            .filter { kotlin.math.abs(it.centerX - anchor.centerX) <= maxOf(anchor.height * 5, anchor.right - anchor.left) }
            .sortedBy { it.top - anchor.bottom }
            .mapNotNull { numericCandidate(it.text) }.firstOrNull()
    }

    private fun normalizeOcrLabel(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "")

    fun financial(text: String, baseConfidence: Double): FinancialParse {
        val lower = text.lowercase()
        val type = when {
            "pokuta" in lower || "штраф" in lower -> FinancialType.PENALTY
            "kompen" in lower || "компен" in lower -> FinancialType.COMPENSATION
            "bonus" in lower || "бонус" in lower -> FinancialType.BONUS
            else -> null
        }
        val amount = moneyRegex.find(text)?.groupValues?.get(1)?.let(::moneyToHellers)
        return FinancialParse(type, amount, dateRegex.find(text)?.value, text.lines().firstOrNull()?.take(120).orEmpty(), baseConfidence * if (type != null && amount != null) 1.0 else .55)
    }

    /** Parses the mixed Rohlík bonus/penalty table (Datum / Částka / Položka / Poznámka). */
    fun financialRows(result: OcrText): List<FinancialRowParse> {
        // Elements keep per-word coordinates when ML Kit merges date + amount + item into one
        // wide line. This is especially important for screenshots cropped below the headers.
        val geometry = result.elements.ifEmpty { result.lines }
        val lines = geometry.map { it.copy(text = cleanLine(it.text)) }.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return financialRows(result.text, result.confidence)
        val rawDateLines = lines.filter { financialDateRegex.containsMatchIn(it.text) }.sortedBy { it.top }
        if (rawDateLines.isEmpty()) return financialRows(result.text, result.confidence)
        val firstDataTop = rawDateLines.first().top
        val dateHeaderX = headerCenterX(lines, "datum", firstDataTop)
        val amountHeaderX = headerCenterX(lines, "castka", firstDataTop)
        val itemHeaderX = headerCenterX(lines, "polozka", firstDataTop)
        val noteHeaderX = headerCenterX(lines, "poznamka", firstDataTop)
        val dateAmountBoundary = if (dateHeaderX != null && amountHeaderX != null) (dateHeaderX + amountHeaderX) / 2 else null
        val dateLines = rawDateLines
            .filter { line -> dateAmountBoundary == null || dateTokenCenterX(line) < dateAmountBoundary }
            .sortedBy { it.top }
        if (dateLines.isEmpty()) return financialRows(result.text, result.confidence)
        val firstDate = dateLines.first()
        val amountCenterX = amountHeaderX ?: (firstDate.right + maxOf(35, firstDate.height * 2))
        val amountLeft = dateHeaderX?.let { (it + amountCenterX) / 2 }
            ?: (firstDate.right - maxOf(4, firstDate.height / 3))
        val amountRight = itemHeaderX?.let { (amountCenterX + it) / 2 }
            ?: noteHeaderX?.let { (amountCenterX + it) / 2 }
            ?: (amountCenterX + maxOf(55, firstDate.height * 3))
        val out = mutableListOf<FinancialRowParse>()
        dateLines.forEachIndexed { index, dateLine ->
            val startY = dateLines.getOrNull(index - 1)?.let { (it.centerY + dateLine.centerY) / 2 }
                ?: dateLine.top - maxOf(8, dateLine.height)
            val endY = dateLines.getOrNull(index + 1)?.let { (dateLine.centerY + it.centerY) / 2 }
                ?: (lines.maxOfOrNull { it.bottom } ?: dateLine.bottom) + 1
            val row = lines.filter { it.centerY >= startY && it.centerY < endY }.sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
            val date = financialDateRegex.find(dateLine.text)?.value ?: return@forEachIndexed
            val amountMatch = moneyEmbeddedAfterDate(dateLine, amountLeft, amountRight)
                ?: moneyFromAmountColumn(row, dateLine, amountCenterX, amountLeft, amountRight)
            val amount = amountMatch?.amountHellers
            val noise = setOf("datum", "částka", "castka", "položka", "polozka")
            val content = row.filterNot { line ->
                line === dateLine || line in (amountMatch?.consumedLines ?: emptySet()) || noise.any { n -> line.text.lowercase().trim() == n }
            }
            val note = content.filter { it.text.lowercase().startsWith("poznámka") || it.text.lowercase().startsWith("poznamka") }
            val main = content.filterNot { it in note }
                .filterNot { moneyRegex.matches(it.text.trim()) }
                .joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ").trim()
            val noteText = note.joinToString(" ") { it.text }.replace(Regex("\\s+"), " ").trim()
            val description = listOf(amountMatch?.inlineDescription.orEmpty(), main, noteText).filter { it.isNotBlank() }.joinToString(" · ").take(500)
            val type = classifyFinancialType(description)
            val confidence = (result.confidence * when { amount != null && type != null -> 1.0; amount != null -> .78; else -> .55 }).coerceIn(0.0, 1.0)
            out += FinancialRowParse(type, amount, date, description, confidence)
        }
        return out.distinctBy { Triple(it.date, it.amountHellers, it.description.lowercase()) }
    }

    fun financialRows(text: String, baseConfidence: Double): List<FinancialRowParse> {
        val clean = text.lines().map(::cleanLine).filter { it.isNotBlank() }
        val dateIndexes = clean.indices.filter { financialDateRegex.containsMatchIn(clean[it]) }
        if (dateIndexes.isEmpty()) {
            val one = financial(text, baseConfidence)
            return if (one.date != null || one.amountHellers != null) listOf(FinancialRowParse(one.type, one.amountHellers, one.date, one.description, one.confidence)) else emptyList()
        }
        return dateIndexes.mapIndexedNotNull { pos, start ->
            val end = dateIndexes.getOrNull(pos + 1) ?: clean.size
            val row = clean.subList(start, end)
            val date = financialDateRegex.find(row.first())?.value ?: return@mapIndexedNotNull null
            val amount = row.asSequence().mapNotNull { moneyRegex.find(it)?.groupValues?.get(1)?.let(::moneyToHellers) }.firstOrNull()
            val description = row.drop(1).filterNot { moneyRegex.containsMatchIn(it) }.joinToString(" ").replace(Regex("\\s+"), " ").trim().take(500)
            FinancialRowParse(classifyFinancialType(description), amount, date, description, (baseConfidence * if (amount != null) .85 else .55).coerceIn(0.0, 1.0))
        }.distinctBy { Triple(it.date, it.amountHellers, it.description.lowercase()) }
    }


    private data class AmountMatch(
        val amountHellers: Long,
        val consumedLines: Set<OcrLine> = emptySet(),
        val inlineDescription: String = ""
    )

    /** ML Kit often returns `date + amount` (and sometimes the item) as one wide line. */
    private fun moneyEmbeddedAfterDate(dateLine: OcrLine, amountLeft: Int, amountRight: Int): AmountMatch? {
        val dateMatch = financialDateRegex.find(dateLine.text) ?: return null
        val afterDate = dateLine.text.substring(dateMatch.range.last + 1)
        val leadingSpaces = afterDate.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        val remainder = afterDate.substring(leadingSpaces)
        val amountMatch = tableAmountPrefixRegex.find(remainder) ?: return null
        if (amountMatch.range.first != 0) return null
        val amount = tableAmountToHellers(amountMatch.groupValues[1]) ?: return null

        val amountStartInLine = dateMatch.range.last + 1 + leadingSpaces + amountMatch.groups[1]!!.range.first
        val amountEndInLine = dateMatch.range.last + 1 + leadingSpaces + amountMatch.groups[1]!!.range.last
        val width = (dateLine.right - dateLine.left).coerceAtLeast(1)
        val textLength = dateLine.text.length.coerceAtLeast(1)
        val amountX = dateLine.left + (((amountStartInLine + amountEndInLine + 1) / 2.0) / textLength * width).toInt()
        if (amountX !in amountLeft..amountRight) return null

        val inlineDescription = remainder.substring(amountMatch.range.last + 1).trim()
        return AmountMatch(amount, inlineDescription = inlineDescription)
    }

    /** Coordinates, not a currency suffix, decide whether a number is an amount. */
    private fun moneyFromAmountColumn(
        row: List<OcrLine>,
        dateLine: OcrLine,
        amountCenterX: Int,
        amountLeft: Int,
        amountRight: Int
    ): AmountMatch? {
        val sameVisualRow = row.filter { line ->
            line !== dateLine &&
                line.bottom >= dateLine.top - 6 &&
                line.top <= dateLine.bottom + 6
        }
        val candidates = sameVisualRow.mapNotNull { line ->
            val parsed = parseAmountToken(line.text) ?: return@mapNotNull null
            val tokenX = if (parsed.second) line.left else line.centerX
            if (tokenX !in amountLeft..amountRight && line.centerX !in amountLeft..amountRight) return@mapNotNull null
            Triple(line, parsed.first, kotlin.math.abs(tokenX - amountCenterX))
        }
        val best = candidates.minWithOrNull(compareBy<Triple<OcrLine, Long, Int>> { it.third }.thenBy { kotlin.math.abs(it.first.centerY - dateLine.centerY) })
            ?: return null
        val currency = sameVisualRow.filter { line ->
            isCurrencyToken(line.text) && line.left >= best.first.left - 8 && line.left <= amountRight + 20
        }.minByOrNull { line -> kotlin.math.abs(line.left - best.first.right) + kotlin.math.abs(line.centerY - best.first.centerY) * 3 }
        val amountIsPrefix = parseAmountToken(best.first.text)?.second == true
        return AmountMatch(best.second, setOfNotNull(best.first.takeUnless { amountIsPrefix }, currency))
    }

    /** Returns hellers and whether the amount is a prefix of a longer OCR line. */
    private fun parseAmountToken(text: String): Pair<Long, Boolean>? {
        val trimmed = text.trim()
        if (financialDateRegex.containsMatchIn(trimmed) || timeRegex.containsMatchIn(trimmed)) return null
        val match = tableAmountPrefixRegex.find(trimmed) ?: return null
        if (match.range.first != 0) return null
        val suffix = trimmed.substring(match.range.last + 1).trim()
        val hasAllowedCurrency = isCurrencyToken(suffix) || Regex("^(?:K\\s*[cčć¢eé6]|CZK)", RegexOption.IGNORE_CASE).containsMatchIn(match.value)
        if (suffix.isNotEmpty() && !hasAllowedCurrency) return null
        val amount = tableAmountToHellers(match.groupValues[1]) ?: return null
        return amount to (match.value.length < trimmed.length)
    }

    private fun tableAmountToHellers(value: String): Long? {
        var compact = value.trim().replace(" ", "").replace("\u00A0", "").replace("'", "").replace("’", "")
        if (compact.count { it == '.' } >= 1 && compact.substringAfterLast('.').length == 3) compact = compact.replace(".", "")
        if (compact.count { it == ',' } >= 1 && compact.substringAfterLast(',').length == 3) compact = compact.replace(",", "")
        compact = compact.replace(',', '.')
        val decimal = compact.toBigDecimalOrNull() ?: return null
        if (decimal < BigDecimal.ZERO || decimal > BigDecimal("100000")) return null
        return decimal.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    private fun isCurrencyToken(text: String): Boolean =
        Regex("^(?:K\\s*[cčć¢eé6]|CZK)[.,:]?$", RegexOption.IGNORE_CASE).matches(text.trim())

    private fun headerCenterX(lines: List<OcrLine>, key: String, firstDataTop: Int): Int? {
        lines.filter { it.bottom <= firstDataTop + 4 }.forEach { line ->
            val normalized = Normalizer.normalize(line.text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
            val index = normalized.indexOf(key)
            if (index >= 0) {
                val width = (line.right - line.left).coerceAtLeast(1)
                return line.left + ((index + key.length / 2.0) / normalized.length.coerceAtLeast(1) * width).toInt()
            }
        }
        return null
    }

    private fun dateTokenCenterX(line: OcrLine): Int {
        val match = financialDateRegex.find(line.text) ?: return line.centerX
        val width = (line.right - line.left).coerceAtLeast(1)
        return line.left + ((match.range.first + match.value.length / 2.0) / line.text.length.coerceAtLeast(1) * width).toInt()
    }

    private fun classifyFinancialType(description: String): FinancialType? {
        val lower = description.lowercase()
        return when {
            listOf("prodlení", "prodleni", "zpožd", "zpozd", "pokuta", "penále", "penale", "hodnota nákupu", "hodnota nakupu", "škoda", "skoda", "штраф").any { it in lower } -> FinancialType.PENALTY
            listOf("kompenz", "náhrada", "nahrada", "компен").any { it in lower } -> FinancialType.BONUS
            listOf("regiony", "region", "převoz", "prevoz", "bonus", "odměna", "odmena", "prémie", "premie", "бонус").any { it in lower } -> FinancialType.BONUS
            else -> null
        }
    }

    /**
     * Parser tuned for the Rohlík courier "Historie zakázek" screen.
     *
     * The OCR engine intentionally stays generic. This parser looks for the stable labels visible on the
     * courier screen (Cena, Dýško, Tašky, Typ platby) and reconstructs the customer card around them.
     * If the screen structure cannot be recognized, we fall back to the previous generic parser.
     */
    fun customers(text: String, baseConfidence: Double): List<CustomerParse> {
        val rohlik = parseRohlikHistory(text, baseConfidence)
        return if (rohlik.isNotEmpty()) rohlik else parseCustomersGeneric(text, baseConfidence)
    }

    /**
     * Preferred parser for photos of the Rohlik courier history screen.
     * ML Kit line coordinates let us pair labels on the left (Dysko/Tasky) with
     * values rendered in the right column, which plain result.text cannot reliably do.
     */
    fun customers(result: OcrText, streetExists: ((String) -> Boolean?)? = null): List<CustomerParse> {
        val structured = parseRohlikLayout(result.lines, result.confidence, streetExists)
        return if (structured.isNotEmpty()) structured else customers(result.text, result.confidence)
    }

    private fun parseRohlikLayout(input: List<OcrLine>, baseConfidence: Double, streetExists: ((String) -> Boolean?)? = null): List<CustomerParse> {
        if (input.isEmpty()) return emptyList()
        val lines = input.map { it.copy(text = cleanLine(it.text)) }.filter { it.text.isNotBlank() }
        // Preserve the visual order from the screenshot. ML Kit does not guarantee
        // that recognized lines arrive in top-to-bottom order, so explicitly sort
        // customer anchors by their vertical position before building customer cards.
        val tipAnchors = lines
            .filter { containsLabel(it.text, "dýško", "dysko", "dyško", "dysko:") }
            .sortedWith(compareBy<OcrLine> { it.top }.thenBy { it.left })
        if (tipAnchors.isEmpty()) return emptyList()

        fun sameRow(a: OcrLine, b: OcrLine): Boolean {
            val tolerance = maxOf(18, maxOf(a.height, b.height) * 2)
            return kotlin.math.abs(a.centerY - b.centerY) <= tolerance
        }

        fun moneyOnRow(anchor: OcrLine): Long? {
            moneyRegex.find(anchor.text)?.groupValues?.get(1)?.let(::moneyToHellers)?.let { return it }
            return lines.asSequence()
                .filter { it !== anchor && sameRow(anchor, it) && it.left >= anchor.left }
                .sortedBy { kotlin.math.abs(it.centerY - anchor.centerY) * 10 + kotlin.math.abs(it.left - anchor.right) }
                .mapNotNull { line ->
                    moneyRegex.find(line.text)?.groupValues?.get(1)?.let(::moneyToHellers)
                        ?: Regex("(-?\\d[\\d ]*[,.]\\d{1,2})").find(line.text)?.groupValues?.get(1)?.let(::moneyToHellers)
                }
                .firstOrNull()
        }

        fun packagesNear(anchor: OcrLine): Int {
            val tasky = lines
                .filter { containsLabel(it.text, "tašky", "tasky") && it.centerY >= anchor.centerY - 10 }
                .minByOrNull { kotlin.math.abs(it.centerY - anchor.centerY) } ?: return 0
            val row = lines.filter { sameRow(tasky, it) }.joinToString(" ") { it.text }
            Regex("\\(\\s*(\\d{1,3})\\s*\\)\\s*$").find(row)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            val cats = Regex("[ACF]\\s*\\(\\s*(\\d{1,3})\\s*\\)", RegexOption.IGNORE_CASE)
                .findAll(row).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            return cats.sum()
        }

        val out = mutableListOf<CustomerParse>()
        for (tipAnchor in tipAnchors) {
            // On the Rohlik screen monetary values live in the right column and can be
            // geometrically closer to Dýško than the address. Prefer a strong Czech-style
            // address (postal code + letters) and only then use the weaker heuristic.
            val aboveTip = lines.filter {
                it.bottom <= tipAnchor.top + 8 &&
                    tipAnchor.top - it.bottom < 460 &&
                    !isMoneyLike(it.text)
            }
            val addressLine = aboveTip.asSequence()
                .filter { isStrongAddress(it.text) }
                .minByOrNull { tipAnchor.top - it.bottom }
                ?: aboveTip.asSequence()
                    .filter { isLikelyAddress(it.text) }
                    .minByOrNull { tipAnchor.top - it.bottom }
                ?: continue

            // ML Kit may split a long address into two OCR lines. A real example is:
            //   Miroslav Rapcak
            //   Jana Zajice
            //   12/25, Praha 17000
            // Without joining the last two lines, "Jana Zajice" looks exactly like a person name.
            // When the strong address line starts with a house number, treat a close left-aligned
            // name-like line immediately above it as the street-name prefix.
            val addressPrefix = if (looksLikeAddressContinuation(addressLine.text)) {
                lines.asSequence()
                    .filter { it !== addressLine && it.bottom <= addressLine.top + 8 }
                    .filter { addressLine.top - it.bottom in 0..95 }
                    .filter { kotlin.math.abs(it.left - addressLine.left) < 140 }
                    .filter { isLikelyStreetNamePrefix(it.text) }
                    .minByOrNull { addressLine.top - it.bottom }
            } else null

            val addressTop = addressPrefix?.top ?: addressLine.top
            val addressLeft = addressPrefix?.left ?: addressLine.left

            // Name and address are left-aligned in Historie zakázek. Requiring roughly the
            // same left column avoids accidentally taking a value/icon from the right side.
            var nameLine = lines.asSequence()
                .filter { it !== addressPrefix }
                .filter { it.bottom <= addressTop + 10 && isLikelyName(it.text) }
                .filter { addressTop - it.bottom < 190 }
                .filter { kotlin.math.abs(it.left - addressLeft) < 180 }
                .minByOrNull { addressTop - it.bottom }
                ?: lines.asSequence()
                    .filter { it !== addressPrefix }
                    .filter { it.bottom <= addressTop + 10 && isLikelyName(it.text) }
                    .filter { addressTop - it.bottom < 190 }
                    .minByOrNull { addressTop - it.bottom }
                ?: continue

            // A Czech street can itself look like a person's name. On the real Rohlik screen
            // ML Kit sometimes splits e.g. "Jana Zajice 12/25, Praha 17000" into
            // "Jana" + "Zajice 12/25, Praha 17000". In that case the nearest line above
            // the address is NOT the customer name; it is the missing first word of the street.
            // Only apply this repair when the candidate name is a single word and the address
            // already looks like a street fragment with a house number. Then require another
            // plausible (preferably multi-word) customer-name line immediately above it.
            var extraStreetPrefix: String? = null
            val candidateName = cleanRohlikName(nameLine.text)
            val candidateWords = candidateName.split(Regex("\\s+")).filter { it.isNotBlank() }
            val baseAddressText = cleanAddress(listOfNotNull(addressPrefix?.text, addressLine.text).joinToString(" "))
            if (candidateWords.size == 1 && looksLikeStreetFragment(baseAddressText)) {
                val previousName = lines.asSequence()
                    .filter { it !== addressPrefix && it !== addressLine && it !== nameLine }
                    .filter { it.bottom <= nameLine.top + 10 }
                    .filter { nameLine.top - it.bottom in 0..130 }
                    .filter { kotlin.math.abs(it.left - nameLine.left) < 180 }
                    .filter { isLikelyName(it.text) }
                    .map { it to cleanRohlikName(it.text) }
                    .filter { (_, cleaned) -> cleaned.split(Regex("\\s+")).count { part -> part.isNotBlank() } >= 2 }
                    .minByOrNull { (line, _) -> nameLine.top - line.bottom }

                if (previousName != null) {
                    val baseStreetRaw = RuianStreetIndex.streetPartFromAddress(baseAddressText)
                    val baseStreet = baseStreetRaw.replace(Regex("^[A-Za-z]\\s+"), "").trim()
                    val longStreet = listOf(candidateName, baseStreet).filter { it.isNotBlank() }.joinToString(" ")
                    val longExists = streetExists?.invoke(longStreet)
                    val shortExists = streetExists?.invoke(baseStreet)
                    if (longExists == true && shortExists != true) {
                        extraStreetPrefix = candidateName
                        nameLine = previousName.first
                    }
                }
            }

            val name = cleanRohlikName(nameLine.text)
            val address = cleanAddress(listOfNotNull(extraStreetPrefix, addressPrefix?.text, addressLine.text).joinToString(" "))
            val parts = name.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (parts.isEmpty() || address.isBlank()) continue

            val tip = moneyOnRow(tipAnchor) ?: 0L
            val packages = packagesNear(tipAnchor)
            var confidence = baseConfidence
            if (tip > 0L || lines.any { sameRow(tipAnchor, it) && it.text.contains("0,00") }) confidence += .05
            if (packages > 0) confidence += .04
            if (postCodeRegex.containsMatchIn(address)) confidence += .05

            out += CustomerParse(
                firstName = parts.first(),
                lastName = parts.drop(1).joinToString(" "),
                address = address,
                normalizedAddress = AddressNormalizer.normalize(address),
                tipHellers = tip,
                packages = packages,
                confidence = confidence.coerceIn(0.0, 1.0)
            )
        }
        return out.distinctBy { Triple((it.firstName + " " + it.lastName).lowercase(), it.normalizedAddress, it.tipHellers to it.packages) }
    }

    private fun parseRohlikHistory(text: String, baseConfidence: Double): List<CustomerParse> {
        val lines = text.lines()
            .map(::cleanLine)
            .filter { it.isNotBlank() }

        if (lines.none { containsLabel(it, "cena") } || lines.none { containsLabel(it, "dýško", "dysko", "dyško") }) {
            return emptyList()
        }

        val priceAnchors = lines.indices.filter { containsLabel(lines[it], "cena") }
        val out = mutableListOf<CustomerParse>()

        for ((anchorPos, priceIndex) in priceAnchors.withIndex()) {
            val nextPrice = priceAnchors.getOrNull(anchorPos + 1) ?: lines.size
            val nextDelivered = (priceIndex + 1 until lines.size)
                .firstOrNull { containsLabel(lines[it], "doručeno", "doruceno") }
                ?: lines.size
            val end = minOf(nextPrice, nextDelivered)

            val searchRange = (priceIndex - 1 downTo maxOf(0, priceIndex - 8))
            val addressIndex = searchRange.firstOrNull { isStrongAddress(lines[it]) }
                ?: searchRange.firstOrNull { isLikelyAddress(lines[it]) }
                ?: continue

            // Plain-text fallback for the same wrapped-address case handled by the
            // coordinate parser above: "Jana Zajice" + "12/25, Praha 17000".
            val prefixIndex = (addressIndex - 1).takeIf { idx ->
                idx >= 0 && looksLikeAddressContinuation(lines[addressIndex]) && isLikelyStreetNamePrefix(lines[idx])
            }
            val nameSearchFrom = (prefixIndex ?: addressIndex) - 1
            var nameIndex = (nameSearchFrom downTo maxOf(0, nameSearchFrom - 3))
                .firstOrNull { isLikelyName(lines[it]) }
                ?: continue

            var extraStreetPrefix: String? = null
            val baseAddress = cleanAddress(listOfNotNull(prefixIndex?.let { lines[it] }, lines[addressIndex]).joinToString(" "))
            val nearestName = cleanRohlikName(lines[nameIndex])
            if (nearestName.split(Regex("\\s+")).count { it.isNotBlank() } == 1 && looksLikeStreetFragment(baseAddress)) {
                val previousNameIndex = (nameIndex - 1 downTo maxOf(0, nameIndex - 3))
                    .firstOrNull { idx ->
                        isLikelyName(lines[idx]) && cleanRohlikName(lines[idx])
                            .split(Regex("\\s+")).count { it.isNotBlank() } >= 2
                    }
                if (previousNameIndex != null) {
                    // Intentionally keep the one-word value as a customer name here.
                    // Only the structured OCR path above may move it into the street, and only
                    // after the official RÚIAN index confirms that interpretation.
                }
            }

            val name = cleanRohlikName(lines[nameIndex])
            if (name.isBlank()) continue
            val address = cleanAddress(listOfNotNull(extraStreetPrefix, prefixIndex?.let { lines[it] }, lines[addressIndex]).joinToString(" "))
            if (address.isBlank()) continue

            val segment = lines.subList(priceIndex, maxOf(priceIndex + 1, end))
            val tip = extractLabeledMoney(segment, "dýško", "dysko", "dyško") ?: 0L
            val packages = extractPackages(segment)

            val nameParts = name.split(Regex("\\s+")).filter { it.isNotBlank() }
            val firstName = nameParts.firstOrNull().orEmpty()
            val lastName = nameParts.drop(1).joinToString(" ")

            var confidence = baseConfidence
            if (postCodeRegex.containsMatchIn(address)) confidence += .06
            if (packages > 0) confidence += .04
            if (segment.any { containsLabel(it, "typ platby") }) confidence += .03

            out += CustomerParse(
                firstName = firstName,
                lastName = lastName,
                address = address,
                normalizedAddress = AddressNormalizer.normalize(address),
                tipHellers = tip,
                packages = packages,
                confidence = confidence.coerceIn(0.0, 1.0)
            )
        }

        return out.distinctBy {
            Triple(
                (it.firstName + " " + it.lastName).lowercase(),
                it.normalizedAddress,
                it.tipHellers to it.packages
            )
        }
    }

    private fun parseCustomersGeneric(text: String, baseConfidence: Double): List<CustomerParse> {
        val lines = text.lines().map(::cleanLine).filter { it.isNotBlank() }
        val results = mutableListOf<CustomerParse>()
        var i = 0
        while (i < lines.size) {
            val name = lines.getOrNull(i).orEmpty()
            val addressIndex = (i + 1 until minOf(i + 5, lines.size)).firstOrNull { isLikelyAddress(lines[it]) }
            if (isLikelyName(name) && addressIndex != null) {
                val address = cleanAddress(lines[addressIndex])
                val nearby = lines.subList(addressIndex, minOf(addressIndex + 8, lines.size))
                val tip = extractLabeledMoney(nearby, "dýško", "dysko", "dyško", "spropitné", "spropitne", "tip") ?: 0L
                val packs = extractPackages(nearby)
                val parts = cleanName(name).split(Regex("\\s+")).filter { it.isNotBlank() }
                results += CustomerParse(
                    parts.firstOrNull().orEmpty(),
                    parts.drop(1).joinToString(" "),
                    address,
                    AddressNormalizer.normalize(address),
                    tip,
                    packs,
                    (baseConfidence * .78).coerceIn(0.0, 1.0)
                )
                i = addressIndex + 1
            } else i++
        }
        return results.distinctBy { Triple((it.firstName + " " + it.lastName).lowercase(), it.normalizedAddress, it.tipHellers to it.packages) }
    }

    private fun extractLabeledMoney(lines: List<String>, vararg labels: String): Long? {
        for (i in lines.indices) {
            if (!containsLabel(lines[i], *labels)) continue
            val candidates = buildList {
                add(lines[i].substringAfter(':', ""))
                for (offset in 1..2) lines.getOrNull(i + offset)?.let(::add)
            }
            candidates.forEach { candidate ->
                moneyRegex.find(candidate)?.groupValues?.get(1)?.let { return moneyToHellers(it) }
                Regex("(-?\\d[\\d ]*[,.]\\d{1,2})").find(candidate)?.groupValues?.get(1)?.let { return moneyToHellers(it) }
            }
        }
        return null
    }

    private fun extractPackages(lines: List<String>): Int {
        for (i in lines.indices) {
            if (!containsLabel(lines[i], "tašky", "tasky")) continue
            val candidate = buildString {
                append(lines[i])
                lines.getOrNull(i + 1)?.let { append(' ').append(it) }
                lines.getOrNull(i + 2)?.let { append(' ').append(it) }
            }
            Regex("\\(\\s*(\\d{1,3})\\s*\\)\\s*$").find(candidate)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            val categories = Regex("[ACF]\\s*\\(\\s*(\\d{1,3})\\s*\\)", RegexOption.IGNORE_CASE)
                .findAll(candidate).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
            if (categories.isNotEmpty()) return categories.sum()
        }
        return 0
    }

    private fun moneyToHellers(value: String): Long? {
        val normalized = value
            .replace(" ", "")
            .replace("\u00A0", "")
            .replace(',', '.')
            .trim()
        return normalized.toBigDecimalOrNull()
            ?.movePointRight(2)
            ?.setScale(0, RoundingMode.HALF_UP)
            ?.longValueExact()
    }

    private fun isMoneyLike(line: String): Boolean {
        val lower = line.lowercase()
        if ("kč" in lower || "czk" in lower) return true
        val compact = line.replace(" ", "").replace('K', 'k')
        return Regex("^-?\\d+[,.]\\d{1,2}(?:kč|kc)?$", RegexOption.IGNORE_CASE).matches(compact)
    }

    private fun isStrongAddress(line: String): Boolean {
        val lower = line.lowercase()
        if (line.length < 8 || isMoneyLike(line)) return false
        if (containsAny(lower, "doručeno", "doruceno", "objednáno", "objednano", "cena", "dýško", "dysko", "tašky", "tasky", "typ platby", "patro", "zvonek", "placeno")) return false
        return postCodeRegex.containsMatchIn(line) && line.any(Char::isLetter)
    }

    private fun isLikelyAddress(line: String): Boolean {
        val lower = line.lowercase()
        if (line.length < 6 || isMoneyLike(line)) return false
        if (containsAny(lower, "doručeno", "doruceno", "objednáno", "objednano", "cena", "dýško", "dysko", "tašky", "tasky", "typ platby", "patro", "zvonek", "placeno")) return false
        if (dateRegex.containsMatchIn(line) && !postCodeRegex.containsMatchIn(line)) return false
        return postCodeRegex.containsMatchIn(line) || (streetNumberRegex.containsMatchIn(line) && line.any(Char::isLetter) && !moneyRegex.containsMatchIn(line))
    }

    private fun looksLikeStreetFragment(line: String): Boolean {
        val cleaned = cleanLine(line)
        if (isMoneyLike(cleaned)) return false
        // Examples: "Zajice 12/25, Praha 17000", "Svetle 3631/14, Chomutov 43001".
        // Must contain letters before a house number; this prevents moving a genuine one-word
        // customer name into unrelated UI text.
        return Regex("^[^0-9]{2,}\\s+\\d{1,5}(?:/\\d{1,5})?").containsMatchIn(cleaned)
    }

    private fun looksLikeAddressContinuation(line: String): Boolean {
        val cleaned = cleanLine(line)
        // Typical wrapped second line: "12/25, Praha 17000". Requiring a postal code and
        // a leading house number keeps this rule narrow and prevents normal names from joining.
        return postCodeRegex.containsMatchIn(cleaned) && Regex("^\\s*\\d{1,5}(?:/\\d{1,5})?").containsMatchIn(cleaned)
    }

    private fun isLikelyStreetNamePrefix(line: String): Boolean {
        val cleaned = cleanLine(line)
        val lower = cleaned.lowercase()
        if (cleaned.length !in 3..60) return false
        if (cleaned.any(Char::isDigit)) return false
        if (containsAny(lower, "historie zakázek", "historie zakazek", "doručeno", "doruceno", "objednáno", "objednano", "cena", "dýško", "dysko", "tašky", "tasky", "typ platby", "patro", "zvonek", "placeno")) return false
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        return words.size in 1..5 && cleaned.count(Char::isLetter) >= 3
    }

    private fun isLikelyName(line: String): Boolean {
        val cleaned = cleanName(line)
        if (cleaned.length !in 2..60) return false
        val lower = cleaned.lowercase()
        if (containsAny(lower, "historie zakázek", "historie zakazek", "doručeno", "doruceno", "objednáno", "objednano", "cena", "dýško", "dysko", "tašky", "tasky", "typ platby", "patro", "zvonek", "placeno")) return false
        if (timeRegex.containsMatchIn(cleaned) || postCodeRegex.containsMatchIn(cleaned)) return false
        if (cleaned.count(Char::isLetter) < 2) return false
        return cleaned.split(Regex("\\s+")).size in 1..5
    }

    private fun cleanLine(value: String): String = value
        .replace('｜', ' ')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun cleanName(value: String): String = value
        .replace(Regex("^[▲△⚠!]+\\s*"), "")
        .replace(Regex("^[0-9]+\\s+"), "")
        .replace(Regex("[>›»]+$"), "")
        .trim(' ', '-', ':')

    private fun cleanRohlikName(value: String): String {
        var cleaned = cleanName(value)
        // The warning triangle before problematic deliveries is frequently OCR-ed as a
        // standalone A/Δ. It is UI chrome, not part of the customer's first name.
        val parts = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (parts.size >= 3 && (parts.first().equals("A", true) || parts.first() == "Δ")) {
            cleaned = parts.drop(1).joinToString(" ")
        }
        return cleaned
    }

    private fun cleanAddress(value: String): String = value
        .replace(Regex("\\s*,\\s*"), ", ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsLabel(line: String, vararg labels: String): Boolean {
        val lower = line.lowercase()
        return labels.any { lower.contains(it.lowercase()) }
    }

    private fun containsAny(value: String, vararg tokens: String): Boolean = tokens.any(value::contains)
}
