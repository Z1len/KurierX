package cz.courierledger.db

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun warehouseToString(v: Warehouse) = v.name
    @TypeConverter fun stringToWarehouse(v: String) = when (v) { "LIBEN" -> Warehouse.LIBOC; else -> Warehouse.valueOf(v) }
    @TypeConverter fun routeTypeToString(v: RouteType) = v.name
    @TypeConverter fun stringToRouteType(v: String) = RouteType.valueOf(v)
    @TypeConverter fun sourceToString(v: DataSource) = v.name
    @TypeConverter fun stringToSource(v: String) = DataSource.valueOf(v)
    @TypeConverter fun shiftStatusToString(v: ShiftStatus) = v.name
    @TypeConverter fun stringToShiftStatus(v: String) = ShiftStatus.valueOf(v)
    @TypeConverter fun financialTypeToString(v: FinancialType) = v.name
    @TypeConverter fun stringToFinancialType(v: String) = FinancialType.valueOf(v)
}
