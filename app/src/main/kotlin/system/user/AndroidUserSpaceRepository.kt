package system.user

import android.content.Context
import android.os.Process
import android.os.UserManager
import features.logs.AndroidAppLogger
import system.toAndroidUserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidUserSpaceRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun currentAndroidUserSpace(): AndroidUserSpace = withContext(Dispatchers.Default) {
        appContext.currentAndroidUserSpace()
    }

    suspend fun listAndroidUserSpaces(): List<AndroidUserSpace> = withContext(Dispatchers.Default) {
        val userManager = appContext.getSystemService(Context.USER_SERVICE) as UserManager
        val currentUserHandle = Process.myUserHandle()
        val currentUserSpace = appContext.currentAndroidUserSpace()
        val profiles = runCatching { userManager.userProfiles }
            .onFailure { error -> AndroidAppLogger.warn(LogTag, "Failed to list Android user profiles", error) }
            .getOrDefault(listOf(currentUserHandle))

        profiles
            .map { userHandle ->
                val userId = userHandle.hashCode()
                AndroidUserSpace(
                    id = userId,
                    name = if (userId == currentUserSpace.id) {
                        currentUserSpace.name
                    } else {
                        "Profile $userId"
                    },
                )
            }
            .distinctBy { user -> user.id }
            .sortedBy { user -> user.id }
            .ifEmpty { listOf(currentUserSpace) }
    }

    private fun Context.currentAndroidUserSpace(): AndroidUserSpace {
        val userId = Process.myUid().toAndroidUserId()
        val userManager = getSystemService(Context.USER_SERVICE) as UserManager
        return AndroidUserSpace(
            id = userId,
            name = userManager.userName.ifBlank { "User $userId" },
        )
    }

    private companion object {
        private const val LogTag = "AndroidUserSpaceRepository"
    }
}
