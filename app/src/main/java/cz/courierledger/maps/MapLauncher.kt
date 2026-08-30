package cz.courierledger.maps

import android.content.Context
import android.content.Intent
import android.net.Uri
import cz.courierledger.settings.MapProvider
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object MapLauncher {
    fun openAddress(context: Context, address: String, provider: MapProvider): Result<Unit> = runCatching {
        require(address.isNotBlank()) { "Адрес пустой" }
        val encoded = URLEncoder.encode(address.trim(), StandardCharsets.UTF_8.toString()).replace("+", "%20")
        val url = when (provider) {
            MapProvider.GOOGLE_MAPS -> "https://www.google.com/maps/search/?api=1&query=$encoded"
            MapProvider.WAZE -> "https://waze.com/ul?q=$encoded&navigate=yes&utm_source=courierledger"
            MapProvider.MAPY_CZ -> "https://mapy.com/fnc/v1/search?query=$encoded"
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
