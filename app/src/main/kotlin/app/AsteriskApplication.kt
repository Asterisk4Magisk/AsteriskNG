package app

import android.app.Application
import system.AndroidAppIconFetcher
import features.logs.AndroidAccessLogRepository
import features.logs.AndroidCoreLogRepository
import features.logs.AndroidLogcatRepository
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader

class AsteriskApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        AndroidLogcatRepository.initialize(applicationContext)
        AndroidCoreLogRepository.initialize(applicationContext)
        AndroidAccessLogRepository.initialize(applicationContext)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(AndroidAppIconFetcher.Factory(this@AsteriskApplication))
                add(AndroidAppIconFetcher.CacheKeyer())
            }
            .build()
    }
}
