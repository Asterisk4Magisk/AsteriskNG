// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import features.subscription.encodeSubscriptionRequestHeaders
import features.subscription.legacySubscriptionRequestHeaders

internal const val AsteriskDatabaseName = "asteriskng.db"

@Database(
    entities = [
        SubscriptionGroupEntity::class,
        ProxyServerEntity::class,
        RouteRuleEntity::class,
        ProxyAppListSelectedAppEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class AsteriskAppDatabase : RoomDatabase() {
    abstract fun appStateDao(): AppStateDao
}

internal val Migration1To2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE subscription_groups ADD COLUMN hwid TEXT NOT NULL DEFAULT ''",
        )
        db.execSQL(
            "ALTER TABLE subscription_groups ADD COLUMN ageSecretKey TEXT NOT NULL DEFAULT ''",
        )
    }
}

internal val Migration2To3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
                CREATE TABLE `subscription_groups_new` (
                    `id` INTEGER NOT NULL,
                    `position` INTEGER NOT NULL,
                    `name` TEXT NOT NULL,
                    `url` TEXT NOT NULL,
                    `userAgent` TEXT NOT NULL,
                    `updateInterval` TEXT NOT NULL,
                `requestHeaders` TEXT,
                    `ageSecretKey` TEXT NOT NULL DEFAULT '',
                    `updateViaProxy` INTEGER NOT NULL,
                    `enabled` INTEGER NOT NULL,
                    `builtIn` INTEGER NOT NULL,
                    `lastUpdatedAtMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
            """.trimIndent(),
        )
        db.execSQL(
            """
                INSERT INTO subscription_groups_new (
                    id, position, name, url, userAgent,
                    updateInterval, requestHeaders, ageSecretKey, updateViaProxy, enabled,
                    builtIn, lastUpdatedAtMillis
                )
                SELECT
                    id, position, name, url, userAgent,
                updateInterval, NULL, ageSecretKey, updateViaProxy, enabled,
                    builtIn, lastUpdatedAtMillis
                FROM subscription_groups
            """.trimIndent(),
        )
        db.query("SELECT id, hwid FROM subscription_groups WHERE hwid <> ''").use { cursor ->
            db.compileStatement("UPDATE subscription_groups_new SET requestHeaders = ? WHERE id = ?").use { update ->
                while (cursor.moveToNext()) {
                    val headers = legacySubscriptionRequestHeaders(cursor.getString(1)) ?: continue
                    update.bindString(1, encodeSubscriptionRequestHeaders(headers))
                    update.bindLong(2, cursor.getLong(0))
                    update.executeUpdateDelete()
                }
            }
        }
        db.execSQL("DROP TABLE subscription_groups")
        db.execSQL("ALTER TABLE subscription_groups_new RENAME TO subscription_groups")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_groups_position ON subscription_groups(position)")
    }
}
