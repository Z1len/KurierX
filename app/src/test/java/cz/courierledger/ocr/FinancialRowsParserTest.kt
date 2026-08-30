package cz.courierledger.ocr

import cz.courierledger.db.FinancialType
import org.junit.Assert.assertEquals
import org.junit.Test

class FinancialRowsParserTest {
    @Test fun readsSplitPositivePenaltyAmount() {
        val lines = listOf(
            OcrLine("21.8.2026", 10, 10, 95, 32, null),
            OcrLine("200", 145, 10, 175, 32, null),
            OcrLine("Kc", 178, 10, 205, 32, null),
            OcrLine("Prodlení při převzetí zásilky k přepravě - zpoždění o 5 až 15 minut", 220, 10, 700, 32, null),
            OcrLine("Poznámka: 10 minut Liboc_6:00-4 kola", 10, 42, 420, 64, null)
        )
        val row = OcrParsers.financialRows(OcrText(lines.joinToString("\n") { it.text }, .95, lines)).single()
        assertEquals(20_000L, row.amountHellers)
        assertEquals(FinancialType.PENALTY, row.type)
    }

    @Test fun compensationContextIsStoredAsBonusCategory() {
        val lines = listOf(
            OcrLine("11.8.2026", 10, 10, 95, 32, null),
            OcrLine("250 Kč", 145, 10, 205, 32, null),
            OcrLine("Kompenzace za trasu", 220, 10, 450, 32, null)
        )
        val row = OcrParsers.financialRows(OcrText(lines.joinToString("\n") { it.text }, .95, lines)).single()
        assertEquals(25_000L, row.amountHellers)
        assertEquals(FinancialType.BONUS, row.type)
    }

    @Test fun readsAmountsFromAmountColumnEvenWithoutCurrencyToken() {
        val cases = listOf(
            Triple("0", 0L, "Prodlení při převzetí zásilky k přepravě - zpoždění do 15 minut"),
            Triple("125", 12_500L, "Hodnota nákupu"),
            Triple("200", 20_000L, "Prodlení při převzetí zásilky k přepravě - zpoždění o 5 až 15 minut"),
            Triple("250", 25_000L, "Regiony"),
            Triple("1500", 150_000L, "Převoz - Liboc")
        )
        cases.forEachIndexed { index, (amountText, expected, description) ->
            val y = 50 + index * 100
            val lines = listOf(
                OcrLine("Datum", 30, 0, 90, 22, null),
                OcrLine("Částka", 135, 0, 200, 22, null),
                OcrLine("Položka", 225, 0, 310, 22, null),
                OcrLine("${21-index}.8.2026", 30, y, 110, y+22, null),
                OcrLine(amountText, 145, y, 190, y+22, null),
                OcrLine(description, 225, y, 700, y+22, null),
                OcrLine("Poznámka: 10 minut Liboc_6:00-4 kola", 30, y+35, 420, y+57, null)
            )
            val row = OcrParsers.financialRows(OcrText(lines.joinToString("\n") { it.text }, .95, lines)).single()
            assertEquals(expected, row.amountHellers)
        }
    }
}
