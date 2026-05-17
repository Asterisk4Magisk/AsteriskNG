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
    open suspend fun loadState(): PersistedAppState? {
        val settings = findAppSettings(SingletonEntityId) ?: return null
        return PersistedAppState(
            settings = settings,
            remoteDnsServers = findRemoteDnsServers(),
            domesticDnsServers = findDomesticDnsServers(),
            dnsHosts = findDnsHosts(),
            externalNetworkInterfaces = findExternalNetworkInterfaces(),
            ignoredNetworkInterfaces = findIgnoredNetworkInterfaces(),
            tproxyPrivateAddressCidrs = findTproxyPrivateAddressCidrs(),
            subscriptionGroups = findSubscriptionGroups(),
            proxyServers = findProxyServerList(),
            routingRules = findRoutingRules(),
            proxyAppListSelectedApps = findProxyAppListSelectedApps(),
        )
    }

    @Transaction
    open suspend fun saveState(previousState: AppState, nextState: AppState, replaceAll: Boolean) {
        saveIfChanged(
            previous = AppSettingsEntity.from(previousState),
            next = AppSettingsEntity.from(nextState),
            replaceAll = replaceAll,
            save = ::saveAppSettings,
        )
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

        if (replaceAll || previousState.remoteDns != nextState.remoteDns) {
            replaceRemoteDnsServers(nextState.remoteDns)
        }

        if (replaceAll || previousState.domesticDns != nextState.domesticDns) {
            replaceDomesticDnsServers(nextState.domesticDns)
        }

        if (replaceAll || previousState.dnsHosts != nextState.dnsHosts) {
            replaceDnsHosts(nextState.dnsHosts)
        }

        if (replaceAll || previousState.externalInterfaces != nextState.externalInterfaces) {
            replaceExternalNetworkInterfaces(nextState.externalInterfaces)
        }

        if (replaceAll || previousState.ignoredInterfaces != nextState.ignoredInterfaces) {
            replaceIgnoredNetworkInterfaces(nextState.ignoredInterfaces)
        }

        if (replaceAll || previousState.privateAddressCidrs != nextState.privateAddressCidrs) {
            replaceTproxyPrivateAddressCidrs(nextState.privateAddressCidrs)
        }

        if (replaceAll || previousState.proxyAppListSelectedApps != nextState.proxyAppListSelectedApps) {
            replaceProxyAppListSelectedApps(nextState.proxyAppListSelectedApps.mapIndexed { index, packageKey ->
                ProxyAppListSelectedAppEntity(position = index, packageKey = packageKey)
            })
        }
    }

    @Query("SELECT * FROM app_settings WHERE id = :id LIMIT 1")
    protected abstract suspend fun findAppSettings(id: String): AppSettingsEntity?

    @Query("SELECT * FROM remote_dns_servers ORDER BY position ASC")
    protected abstract suspend fun findRemoteDnsServers(): List<RemoteDnsServerEntity>

    @Query("SELECT * FROM domestic_dns_servers ORDER BY position ASC")
    protected abstract suspend fun findDomesticDnsServers(): List<DomesticDnsServerEntity>

    @Query("SELECT * FROM dns_hosts ORDER BY position ASC")
    protected abstract suspend fun findDnsHosts(): List<DnsHostEntity>

    @Query("SELECT * FROM external_network_interfaces ORDER BY position ASC")
    protected abstract suspend fun findExternalNetworkInterfaces(): List<ExternalNetworkInterfaceEntity>

    @Query("SELECT * FROM ignored_network_interfaces ORDER BY position ASC")
    protected abstract suspend fun findIgnoredNetworkInterfaces(): List<IgnoredNetworkInterfaceEntity>

    @Query("SELECT * FROM tproxy_private_address_cidrs ORDER BY position ASC")
    protected abstract suspend fun findTproxyPrivateAddressCidrs(): List<TproxyPrivateAddressCidrEntity>

    @Query("SELECT * FROM subscription_groups ORDER BY position ASC")
    protected abstract suspend fun findSubscriptionGroups(): List<SubscriptionGroupEntity>

    @Query("SELECT * FROM proxy_servers ORDER BY position ASC")
    protected abstract suspend fun findProxyServerList(): List<ProxyServerEntity>

    @Query("SELECT * FROM routing_rules ORDER BY position ASC")
    protected abstract suspend fun findRoutingRules(): List<RouteRuleEntity>

    @Query("SELECT * FROM proxy_app_list_selected_apps ORDER BY position ASC")
    protected abstract suspend fun findProxyAppListSelectedApps(): List<ProxyAppListSelectedAppEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun saveAppSettings(entity: AppSettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRemoteDnsServers(entities: List<RemoteDnsServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDomesticDnsServers(entities: List<DomesticDnsServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertDnsHosts(entities: List<DnsHostEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertExternalNetworkInterfaces(entities: List<ExternalNetworkInterfaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertIgnoredNetworkInterfaces(entities: List<IgnoredNetworkInterfaceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertTproxyPrivateAddressCidrs(entities: List<TproxyPrivateAddressCidrEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSubscriptionGroups(entities: List<SubscriptionGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProxyServerList(entities: List<ProxyServerEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertRoutingRules(entities: List<RouteRuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertProxyAppListSelectedApps(entities: List<ProxyAppListSelectedAppEntity>)

    @Query("DELETE FROM remote_dns_servers")
    protected abstract suspend fun deleteRemoteDnsServers()

    @Query("DELETE FROM domestic_dns_servers")
    protected abstract suspend fun deleteDomesticDnsServers()

    @Query("DELETE FROM dns_hosts")
    protected abstract suspend fun deleteDnsHosts()

    @Query("DELETE FROM external_network_interfaces")
    protected abstract suspend fun deleteExternalNetworkInterfaces()

    @Query("DELETE FROM ignored_network_interfaces")
    protected abstract suspend fun deleteIgnoredNetworkInterfaces()

    @Query("DELETE FROM tproxy_private_address_cidrs")
    protected abstract suspend fun deleteTproxyPrivateAddressCidrs()

    @Query("DELETE FROM subscription_groups")
    protected abstract suspend fun deleteSubscriptionGroups()

    @Query("DELETE FROM proxy_servers")
    protected abstract suspend fun deleteProxyServerList()

    @Query("DELETE FROM routing_rules")
    protected abstract suspend fun deleteRoutingRules()

    @Query("DELETE FROM proxy_app_list_selected_apps")
    protected abstract suspend fun deleteProxyAppListSelectedApps()

    private suspend fun replaceRemoteDnsServers(values: List<String>) {
        replaceOrderedValues(values, ::deleteRemoteDnsServers, ::RemoteDnsServerEntity, ::insertRemoteDnsServers)
    }

    private suspend fun replaceDomesticDnsServers(values: List<String>) {
        replaceOrderedValues(values, ::deleteDomesticDnsServers, ::DomesticDnsServerEntity, ::insertDomesticDnsServers)
    }

    private suspend fun replaceDnsHosts(values: List<String>) {
        replaceOrderedValues(values, ::deleteDnsHosts, ::DnsHostEntity, ::insertDnsHosts)
    }

    private suspend fun replaceExternalNetworkInterfaces(values: List<String>) {
        replaceOrderedValues(
            values = values,
            delete = ::deleteExternalNetworkInterfaces,
            create = ::ExternalNetworkInterfaceEntity,
            insert = ::insertExternalNetworkInterfaces,
        )
    }

    private suspend fun replaceIgnoredNetworkInterfaces(values: List<String>) {
        replaceOrderedValues(
            values = values,
            delete = ::deleteIgnoredNetworkInterfaces,
            create = ::IgnoredNetworkInterfaceEntity,
            insert = ::insertIgnoredNetworkInterfaces,
        )
    }

    private suspend fun replaceTproxyPrivateAddressCidrs(values: List<String>) {
        replaceOrderedValues(
            values = values,
            delete = ::deleteTproxyPrivateAddressCidrs,
            create = ::TproxyPrivateAddressCidrEntity,
            insert = ::insertTproxyPrivateAddressCidrs,
        )
    }

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

    private suspend fun <T> replaceOrderedValues(
        values: List<String>,
        delete: suspend () -> Unit,
        create: (position: Int, value: String) -> T,
        insert: suspend (List<T>) -> Unit,
    ) {
        delete()
        insert(values.mapIndexed { index, value -> create(index, value) })
    }

    private suspend fun <T> saveIfChanged(
        previous: T,
        next: T,
        replaceAll: Boolean,
        save: suspend (T) -> Unit,
    ) {
        if (replaceAll || previous != next) {
            save(next)
        }
    }
}
