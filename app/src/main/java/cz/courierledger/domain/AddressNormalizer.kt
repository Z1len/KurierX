package cz.courierledger.domain

import java.text.Normalizer
import java.util.Locale

object AddressNormalizer {
    private val noise = setOf("ceska republika", "czech republic", "cz")

    fun normalize(input: String): String {
        val ascii = Normalizer.normalize(input.lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("roh?lik\\s*point".toRegex(), " rp ")
            .replace("[^a-z0-9]+".toRegex(), " ")
            .trim()
        val tokens = ascii.split(" ").filter { it.isNotBlank() }
        val filtered = tokens.filterNot { token -> noise.any { n -> n.split(" ").contains(token) } }
        val house = filtered.filter { it.any(Char::isDigit) }
        val words = filtered.filterNot { it.any(Char::isDigit) }.sorted()
        return (words + house.sorted()).joinToString(" ")
    }

    fun similarity(a: String, b: String): Double {
        val x = normalize(a); val y = normalize(b)
        if (x == y) return 1.0
        if (x.isBlank() || y.isBlank()) return 0.0
        val xs = x.split(" ").toSet(); val ys = y.split(" ").toSet()
        return xs.intersect(ys).size.toDouble() / xs.union(ys).size.coerceAtLeast(1)
    }
}
