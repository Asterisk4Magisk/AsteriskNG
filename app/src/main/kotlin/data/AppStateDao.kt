// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import app.AppState

@Dao
internal abstract class AppStateDao {
    @Transaction
    open suspend fun loadState(): PersistedAppState {
        return PersistedAppState(
            subscriptionGroups = findSubscriptionGroups(),
            proxyServers = findProxyServerList(),
            routingRules = findRoutingRules(),
            proxyAppListSelectedApps = findProxyAppListSelectedApps(),
        )
    }

    @Transaction
    open suspend fun saveState(previousState: AppState, nextState: AppState, replaceAll: Boolean) {
        saveLists(previousState, nextState, replaceAll)
    }

    private suspend fun saveLists(previousState: AppState, nextState: AppState, replaceAll: Boolean) {
        if (replaceAll || previousState.subscriptionGroups != nextState.subscriptionGroups) {
            replaceSubscriptionGroups(nextState.subscriptionGroups.mapIndexed { index, group ->
                SubscriptionGroupEntity.from(index, group)
            })
        }

        if (replaceAll || !previousState.proxyServers.hasSamePersistedContent(nextState.proxyServers)) {
            replaceProxyServerList(nextState.proxyServers.mapIndexed { index, server ->
                ProxyServerEntity.from(index, server)
            })
        }

        if (replaceAll || previousState.routeRules != nextState.routeRules) {
            replaceRoutingRules(nextState.routeRules.mapIndexed { index, rule ->
                RouteRuleEntity.from(index, rule)
            })
        }

        if (replaceAll || previousState.proxyAppListSelectedApps != nextState.proxyAppListSelectedApps) {
            replaceProxyAppListSelectedApps(nextState.proxyAppListSelectedApps.mapIndexed { index, packageKey ->
                ProxyAppListSelectedAppEntity(position = index, packageKey = packageKey)
            })
        }
    }

    @Query("SELECT * FROM subscription_groups ORDER BY position ASC")
    protected abstract suspend fun findSubscriptionGroups(): List<SubscriptionGroupEntity>

    @Query("SELECT * FROM proxy_servers ORDER BY position ASC")
    protected abstract suspend fun findProxyServerList(): List<ProxyServerEntity>

    @Query("SELECT * FROM routing_rules ORDER BY position ASC")
    protected abstract suspend fun findRoutingRules(): List<RouteRuleEntity>

    @Query("SELECT * FROM proxy_app_list_selected_apps ORDER BY position ASC")
    protected abstract suspend fun findProxyAppListSelectedApps(): List<ProxyAppListSelectedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSubscriptionGroups(entities: List<SubscriptionGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProxyServerList(entities: List<ProxyServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRoutingRules(entities: List<RouteRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>)

    @Query("DELETE FROM subscription_groups")
    protected abstract suspend fun deleteSubscriptionGroups()

    @Query("DELETE FROM proxy_servers")
    protected abstract suspend fun deleteProxyServerList()

    @Query("DELETE FROM routing_rules")
    protected abstract suspend fun deleteRoutingRules()

    @Query("DELETE FROM proxy_app_list_selected_apps")
    protected abstract suspend fun deleteProxyAppListSelectedApps()

    private suspend fun replaceSubscriptionGroups(entities: List<SubscriptionGroupEntity>) {
        deleteSubscriptionGroups()
        insertSubscriptionGroups(entities)
    }

    private suspend fun replaceProxyServerList(entities: List<ProxyServerEntity>) {
        deleteProxyServerList()
        insertProxyServerList(entities)
    }

    private suspend fun replaceRoutingRules(entities: List<RouteRuleEntity>) {
        deleteRoutingRules()
        insertRoutingRules(entities)
    }

    private suspend fun replaceProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>) {
        deleteProxyAppListSelectedApps()
        insertProxyAppListSelectedApps(entities)
    }
}
