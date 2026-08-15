// Copyright 2026, AsteriskNG contributors
// SPDX-License-Identifier: GPL-3.0

package app

import android.app.Application
import system.AndroidAppIconFetcher
import features.logs.AndroidAccessLogRepository
import features.logs.AndroidAsteriskdLogRepository
import features.logs.AndroidCoreLogRepository
import features.logs.AndroidLogcatRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import data.AppSettingsPreferences
import data.AndroidAppStateStore
import features.subscription.runtime.AndroidSubscriptionFetcher
import features.subscription.runtime.AndroidSubscriptionScheduleGateway
import features.subscription.runtime.SubscriptionScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class AsteriskApplication : Application(), SingletonImageLoader.Factory {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val stateStore: AndroidAppStateStore by lazy {
        AndroidAppStateStore.get(applicationContext)
    }
    internal val subscriptionFetcher: AndroidSubscriptionFetcher by lazy {
        AndroidSubscriptionFetcher(applicationContext)
    }
    private val subscriptionScheduler: SubscriptionScheduler by lazy {
        SubscriptionScheduler(AndroidSubscriptionScheduleGateway(applicationContext))
    }

    override fun onCreate() {
        super.onCreate()
        AppSettingsPreferences(applicationContext).getOrCreateSubscriptionHwid()
        AndroidLogcatRepository.initialize(applicationContext)
        AndroidCoreLogRepository.initialize(applicationContext)
        AndroidAccessLogRepository.initialize(applicationContext)
        AndroidAsteriskdLogRepository.initialize(applicationContext)
        appScope.launch {
            stateStore.state
                .map { state ->
                    state.subscriptionGroups.map { group ->
                        SubscriptionScheduleKey(
                            id = group.id,
                            url = group.url,
                            interval = group.updateInterval,
                            enabled = group.enabled,
                        )
                    }
                }
                .distinctUntilChanged()
                .collect {
                    subscriptionScheduler.reconcile(stateStore.state.value.subscriptionGroups)
                }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AndroidAppIconFetcher.Factory(this@AsteriskApplication))
                add(AndroidAppIconFetcher.CacheKeyer())
            }
            .build()
    }

    private data class SubscriptionScheduleKey(
        val id: Int,
        val url: String,
        val interval: String,
        val enabled: Boolean,
    )
}
