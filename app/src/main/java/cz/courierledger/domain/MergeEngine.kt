package cz.courierledger.domain

import androidx.room.withTransaction
import cz.courierledger.db.*

class MergeEngine(private val db: AppDatabase) {
    /**
     * Groups all orders with the same normalized address into one reversible merge group.
     * If the user previously split a group for this address, the inactive group acts as a
     * manual override and the address is not auto-merged again.
     */
    suspend fun mergeExactAddresses(routeId: Long): Int = db.withTransaction {
        val dao = db.courierDao()
        val orders = dao.ordersForRoute(routeId)
        val history = dao.allMergeGroups(routeId)
        val suppressedAddresses = history.filter { !it.active }.map { it.normalizedAddress }.toSet()
        var changedGroups = 0

        orders.groupBy { it.normalizedAddress }
            .filterKeys { it.isNotBlank() && it !in suppressedAddresses }
            .values
            .filter { it.size > 1 }
            .forEach { same ->
                val existingIds = same.mapNotNull { it.mergeGroupId }.distinct()
                val targetId = existingIds.firstOrNull()
                    ?: dao.insertMergeGroup(
                        MergeGroupEntity(
                            routeId = routeId,
                            reason = "Одинаковый адрес",
                            normalizedAddress = same.first().normalizedAddress
                        )
                    )

                dao.assignMergeGroup(same.map { it.id }, targetId)
                val obsolete = existingIds.filter { it != targetId }
                if (obsolete.isNotEmpty()) dao.deactivateMergeGroups(obsolete)

                if (existingIds.isEmpty() || obsolete.isNotEmpty() || same.any { it.mergeGroupId != targetId }) {
                    changedGroups++
                    dao.audit(
                        AuditLogEntity(
                            action = "AUTO_MERGE",
                            entityType = "MergeGroup",
                            entityId = targetId.toString(),
                            oldValue = null,
                            newValue = "${same.size} clients -> 1 order",
                            source = DataSource.AUTO_CALC
                        )
                    )
                }
            }
        changedGroups
    }

    suspend fun split(groupId: Long) = db.withTransaction {
        val dao = db.courierDao()
        val group = dao.mergeGroup(groupId) ?: return@withTransaction
        dao.clearMergeGroup(groupId)
        dao.deactivateMergeGroup(groupId)
        dao.audit(
            AuditLogEntity(
                action = "SPLIT_MERGE",
                entityType = "MergeGroup",
                entityId = groupId.toString(),
                oldValue = "active",
                newValue = "split; auto-merge suppressed for ${group.normalizedAddress}",
                source = DataSource.USER_CORRECTION
            )
        )
    }

    suspend fun splitAll(routeId: Long) = db.withTransaction {
        val dao = db.courierDao()
        val groups = dao.activeMergeGroups(routeId)
        groups.forEach { group ->
            dao.clearMergeGroup(group.id)
            dao.deactivateMergeGroup(group.id)
            dao.audit(
                AuditLogEntity(
                    action = "SPLIT_MERGE",
                    entityType = "MergeGroup",
                    entityId = group.id.toString(),
                    oldValue = "active",
                    newValue = "split; auto-merge suppressed for ${group.normalizedAddress}",
                    source = DataSource.USER_CORRECTION
                )
            )
        }
    }
}
