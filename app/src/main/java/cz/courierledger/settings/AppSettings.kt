package cz.courierledger.settings

import android.content.Context
import cz.courierledger.db.Warehouse

enum class MapProvider(val label: String) {
    GOOGLE_MAPS("Google Maps"),
    WAZE("Waze"),
    MAPY_CZ("Mapy.cz")
}

class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("courier_ledger_settings", Context.MODE_PRIVATE)

    var mapProvider: MapProvider
        get() = runCatching { MapProvider.valueOf(prefs.getString(KEY_MAP_PROVIDER, null) ?: MapProvider.GOOGLE_MAPS.name) }
            .getOrDefault(MapProvider.GOOGLE_MAPS)
        set(value) { prefs.edit().putString(KEY_MAP_PROVIDER, value.name).apply() }

    var defaultWarehouse: Warehouse
        get() = runCatching { Warehouse.valueOf(prefs.getString(KEY_DEFAULT_WAREHOUSE, null) ?: Warehouse.LIBOC.name) }.getOrDefault(Warehouse.LIBOC)
        set(value) { prefs.edit().putString(KEY_DEFAULT_WAREHOUSE, value.name).apply() }

    var homeAddress: String
        get() = prefs.getString(KEY_HOME_ADDRESS, "") ?: ""
        set(value) { prefs.edit().putString(KEY_HOME_ADDRESS, value).apply() }
    var homeLat: Double?
        get() = prefs.getString(KEY_HOME_LAT, null)?.toDoubleOrNull()
        set(value) { prefs.edit().apply { if(value==null) remove(KEY_HOME_LAT) else putString(KEY_HOME_LAT,value.toString()) }.apply() }
    var homeLon: Double?
        get() = prefs.getString(KEY_HOME_LON, null)?.toDoubleOrNull()
        set(value) { prefs.edit().apply { if(value==null) remove(KEY_HOME_LON) else putString(KEY_HOME_LON,value.toString()) }.apply() }
    var fuelConsumptionLPer100Km: Double
        get() = prefs.getString(KEY_CONSUMPTION, "7.0")?.toDoubleOrNull()?.coerceIn(1.0,30.0) ?: 7.0
        set(value) { prefs.edit().putString(KEY_CONSUMPTION, value.coerceIn(1.0,30.0).toString()).apply() }
    var lastDieselPriceKc: Double
        get() = prefs.getString(KEY_DIESEL_PRICE, "0")?.toDoubleOrNull() ?: 0.0
        set(value) { prefs.edit().putString(KEY_DIESEL_PRICE, value.toString()).putLong(KEY_DIESEL_UPDATED,System.currentTimeMillis()).apply() }
    val lastDieselUpdatedAt: Long get() = prefs.getLong(KEY_DIESEL_UPDATED,0L)
    fun warehouseDistanceKm(warehouse: Warehouse): Double? = prefs.getString("distance_${warehouse.name}",null)?.toDoubleOrNull()
    fun setWarehouseDistanceKm(warehouse: Warehouse, value: Double) { prefs.edit().putString("distance_${warehouse.name}",value.toString()).apply() }

    companion object {
        private const val KEY_MAP_PROVIDER = "map_provider"
        private const val KEY_DEFAULT_WAREHOUSE = "default_warehouse"
        private const val KEY_HOME_ADDRESS = "home_address"
        private const val KEY_HOME_LAT = "home_lat"
        private const val KEY_HOME_LON = "home_lon"
        private const val KEY_CONSUMPTION = "fuel_consumption"
        private const val KEY_DIESEL_PRICE = "diesel_price"
        private const val KEY_DIESEL_UPDATED = "diesel_updated"
    }
}
