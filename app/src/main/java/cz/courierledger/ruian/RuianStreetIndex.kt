package cz.courierledger.ruian

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * Small offline index of official Czech street names from the ČÚZK RÚIAN UI_ULICE catalogue.
 * The downloaded catalogue is reduced to normalized street names only, keeping lookup fast and storage modest.
 */
class RuianStreetIndex(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "ruian")
    private val indexFile = File(dir, "streets.txt")
    private val prefs = appContext.getSharedPreferences("ruian_street_index", Context.MODE_PRIVATE)

    @Volatile private var cache: Set<String>? = null

    data class Info(val available: Boolean, val streetCount: Int, val updatedAt: Long?)

    fun info(): Info = Info(
        available = indexFile.exists() && indexFile.length() > 0,
        streetCount = prefs.getInt(KEY_COUNT, 0),
        updatedAt = prefs.getLong(KEY_UPDATED, 0L).takeIf { it > 0L }
    )

    /** Returns null while the official index is not installed. */
    fun containsStreet(streetName: String): Boolean? {
        if (!indexFile.exists()) return null
        val normalized = normalizeStreetName(streetName)
        if (normalized.isBlank()) return false
        val local = cache ?: synchronized(this) {
            cache ?: loadIndex().also { cache = it }
        }
        return normalized in local
    }

    suspend fun updateFromOfficialSource(): Info = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val temp = File(dir, "streets.txt.tmp")
        val names = LinkedHashSet<String>(90_000)
        val connection = URL(OFFICIAL_ZIP).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 90_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "CourierLedger/1.0 Android")
        try {
            val code = connection.responseCode
            require(code in 200..299) { "ČÚZK вернул HTTP $code" }
            val csvTemp = File(dir, "UI_ULICE.csv.tmp")
            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && (entry.isDirectory || !entry.name.endsWith(".csv", ignoreCase = true))) {
                    zip.closeEntry(); entry = zip.nextEntry
                }
                require(entry != null) { "В архиве ČÚZK не найден CSV" }
                csvTemp.outputStream().buffered().use { out -> zip.copyTo(out) }
            }
            val charset = detectCharset(csvTemp)
            csvTemp.bufferedReader(charset).useLines { lines ->
                var first = true
                lines.forEach { line ->
                    if (first) { first = false; return@forEach }
                    val cols = parseSemicolonCsv(line)
                    if (cols.size >= 2) {
                        val normalized = normalizeStreetName(cols[1])
                        if (normalized.length >= 2) names += normalized
                    }
                }
            }
            csvTemp.delete()
            require(names.size > 1_000) { "Каталог улиц выглядит неполным (${names.size})" }
            temp.bufferedWriter(Charsets.UTF_8).use { out -> names.forEach { out.appendLine(it) } }
            if (indexFile.exists()) indexFile.delete()
            require(temp.renameTo(indexFile)) { "Не удалось заменить локальный каталог улиц" }
            cache = names
            prefs.edit().putInt(KEY_COUNT, names.size).putLong(KEY_UPDATED, System.currentTimeMillis()).apply()
            info()
        } finally {
            connection.disconnect()
            temp.takeIf { it.exists() }?.delete()
            File(dir, "UI_ULICE.csv.tmp").takeIf { it.exists() }?.delete()
        }
    }

    private fun loadIndex(): Set<String> = indexFile.useLines(Charsets.UTF_8) { it.filter(String::isNotBlank).toHashSet() }

    private fun detectCharset(file: File): Charset {
        val sample = file.inputStream().use { input -> ByteArray(64 * 1024).let { buf -> val n = input.read(buf); if (n <= 0) ByteArray(0) else buf.copyOf(n) } }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(sample))
            Charsets.UTF_8
        } catch (_: Exception) { Charset.forName("windows-1250") }
    }

    companion object {
        const val OFFICIAL_ZIP = "https://services.cuzk.cz/sestavy/cis/UI_ULICE.zip"
        private const val KEY_COUNT = "street_count"
        private const val KEY_UPDATED = "updated_at"

        fun normalizeStreetName(value: String): String {
            val noMarks = Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            return noMarks.lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9 ]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        fun streetPartFromAddress(address: String): String {
            val normalizedSpaces = address.replace(Regex("\\s+"), " ").trim()
            val numberStart = Regex("\\s+\\d{1,5}(?:/\\d{1,5}[a-zA-Z]?)?(?:[,\\s]|$)").find(normalizedSpaces)?.range?.first
            return if (numberStart != null) normalizedSpaces.substring(0, numberStart).trim(' ', ',')
            else normalizedSpaces.substringBefore(',').trim()
        }

        private fun parseSemicolonCsv(line: String): List<String> {
            val out = ArrayList<String>(6)
            val current = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    ch == '"' && quoted && i + 1 < line.length && line[i + 1] == '"' -> { current.append('"'); i++ }
                    ch == '"' -> quoted = !quoted
                    ch == ';' && !quoted -> { out += current.toString(); current.setLength(0) }
                    else -> current.append(ch)
                }
                i++
            }
            out += current.toString()
            return out
        }
    }
}
