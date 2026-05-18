package data

import androidx.room.Database
import androidx.room.RoomDatabase

internal const val AsteriskDatabaseName = "asteriskng.db"

@Database(
    entities = [
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
