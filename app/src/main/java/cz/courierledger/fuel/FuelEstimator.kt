package cz.courierledger.fuel

import android.content.Context
import android.location.Geocoder
import cz.courierledger.db.Warehouse
import cz.courierledger.settings.AppSettings
import cz.courierledger.ruian.RuianStreetIndex
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.util.Locale
import kotlin.math.roundToLong

object Warehouses {
    val addresses = mapOf(
        Warehouse.LIBOC to "U Prioru 884/4, 161 00 Praha 6",
        Warehouse.CHRASTANY to "Severní 253, 252 19 Chrášťany",
        Warehouse.HORNI_POCERNICE to "U Tabulky 3091, 193 00 Praha 20"
    )
}

data class HomeValidation(val validStreet: Boolean?, val lat: Double?, val lon: Double?, val message: String)
data class FuelEstimate(val amountHellers: Long, val shifts: Int, val totalRoundTripKm: Double, val dieselPriceKc: Double, val consumption: Double)

class FuelEstimator(private val context: Context) {
    private val settings=AppSettings(context)

    @Suppress("DEPRECATION")
    fun validateAndGeocodeHome(address:String): HomeValidation {
        val street=RuianStreetIndex.streetPartFromAddress(address)
        val valid=RuianStreetIndex(context).containsStreet(street)
        val loc=runCatching {
            Geocoder(context, Locale("cs","CZ")).getFromLocationName(address,1)?.firstOrNull()
        }.getOrNull()
        return HomeValidation(valid, loc?.latitude, loc?.longitude, when {
            valid==false -> "Улица не найдена в RÚIAN"
            loc==null -> "Улица найдена, но координаты пока не удалось получить"
            else -> "Адрес подтверждён"
        })
    }

    @Suppress("DEPRECATION")
    fun refreshDistances(homeLat:Double, homeLon:Double): Map<Warehouse,Double> {
        val geocoder=Geocoder(context,Locale("cs","CZ"))
        return Warehouses.addresses.mapNotNull { (wh,address) ->
            val w=runCatching { geocoder.getFromLocationName(address,1)?.firstOrNull() }.getOrNull() ?: return@mapNotNull null
            val km=routeKm(homeLat,homeLon,w.latitude,w.longitude) ?: return@mapNotNull null
            settings.setWarehouseDistanceKm(wh,km)
            wh to km
        }.toMap()
    }

    private fun routeKm(lat1:Double,lon1:Double,lat2:Double,lon2:Double):Double? {
        val url=URL("https://router.project-osrm.org/route/v1/driving/$lon1,$lat1;$lon2,$lat2?overview=false&alternatives=false")
        val c=url.openConnection() as HttpURLConnection
        c.connectTimeout=12000;c.readTimeout=12000;c.setRequestProperty("User-Agent","CourierLedger/2.1")
        return try {
            if(c.responseCode !in 200..299) return null
            val text=c.inputStream.bufferedReader().readText()
            Regex("\\\"distance\\\"\\s*:\\s*([0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()?.div(1000.0)
        } finally { c.disconnect() }
    }

    /** ČEPRO does not publish a documented public price API. We therefore read only its official station page;
     * if the current HTML does not expose prices, callers keep the last successfully cached official value. */
    fun refreshOfficialDieselPrice(): Double? {
        val urls=listOf("https://www.ceproas.cz/eurooil/cerpaci-stanice","https://www.ceproas.cz/eurooil")
        val values=mutableListOf<Double>()
        for(raw in urls) runCatching {
            val c=URL(raw).openConnection() as HttpURLConnection
            c.connectTimeout=12000;c.readTimeout=15000;c.instanceFollowRedirects=true;c.setRequestProperty("User-Agent","CourierLedger/2.1 Android")
            try {
                if(c.responseCode in 200..299) {
                    val html=c.inputStream.bufferedReader().readText()
                    Regex("(?i)(?:nafta|diesel)[^0-9]{0,80}([2-9][0-9][,.][0-9]{1,2})\\s*(?:Kč|Kc)").findAll(html).forEach { m ->
                        m.groupValues[1].replace(',','.').toDoubleOrNull()?.takeIf { it in 20.0..80.0 }?.let(values::add)
                    }
                }
            } finally { c.disconnect() }
        }
        if(values.isEmpty()) return null
        val avg=values.average()
        settings.lastDieselPriceKc=avg
        return avg
    }

    fun estimate(warehouses:List<Warehouse>):FuelEstimate? {
        val price=settings.lastDieselPriceKc
        if(price<=0.0 || warehouses.isEmpty()) return null
        val distances=warehouses.mapNotNull { settings.warehouseDistanceKm(it) }
        if(distances.size!=warehouses.size) return null
        val roundTrip=distances.sumOf { it*2.0 }
        val liters=roundTrip*settings.fuelConsumptionLPer100Km/100.0
        return FuelEstimate((liters*price*100.0).roundToLong(),warehouses.size,roundTrip,price,settings.fuelConsumptionLPer100Km)
    }
}
