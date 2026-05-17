package data

import androidx.room.Database
import androidx.room.RoomDatabase

internal const val AsteriskDatabaseName = "asteriskng.db"

@Database(
    entities = [
        AppSettingsEntity::class,
        RemoteDnsServerEntity::class,
        DomesticDnsServerEntity::class,
        DnsHostEntity::class,
        ExternalNetworkInterfaceEntity::class,
        IgnoredNetworkInterfaceEntity::class,
        TproxyPrivateAddressCidrEntity::class,
        SubscriptionGroupEntity::class,
        ProxyServerEntity::class,
        RouteRuleEntity::class,
        ProxyAppListSelectedAppEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
internal abstract class AsteriskAppDatabase : RoomDatabase() {
    abstract fun appStateDao(): AppStateDao
}
