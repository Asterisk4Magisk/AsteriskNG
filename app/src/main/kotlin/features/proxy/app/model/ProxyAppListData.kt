package features.proxy.app.model

import androidx.compose.runtime.Immutable
import system.ANDROID_PACKAGE_SCOPE_ALL
import system.ANDROID_PACKAGE_SCOPE_USER
import system.toAndroidAppId
import system.toAndroidUserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import system.AndroidPackageProvider
import system.AndroidUser
import system.InstalledPackageInfo
import system.user.AndroidUserSpace

@Immutable
data class ProxyAppIconRequest(
    val packageName: String,
    val sizePx: Int,
)

@Immutable
internal data class AppPackageEntry(
    val packageName: String,
    val appLabel: String? = null,
    val userId: Int? = null,
    val uid: Int? = null,
    val system: Boolean = false,
)

@Immutable
internal data class ProxyAppListUserSpaceTabUi(
    val id: Int,
    val label: String,
    val checkedCount: Int,
)

@Immutable
internal data class ProxyAppListItem(
    val key: String,
    val app: AppPackageEntry,
    val sharedUid: Boolean,
    val selectionKeys: List<String>,
)

@Immutable
internal data class ProxyAppListPreparedData(
    val displayedUserSpaces: List<AndroidUserSpace> = emptyList(),
    val allItemsByUser: Map<Int, List<ProxyAppListItem>> = emptyMap(),
    val visibleItemsByUser: Map<Int, List<ProxyAppListItem>> = emptyMap(),
) {
    companion object {
        val Empty = ProxyAppListPreparedData()
    }
}

internal suspend fun AndroidPackageProvider.loadProxyAppListPackages(
    showSystemApps: Boolean,
    currentUserOnly: Boolean,
): List<AppPackageEntry> = withContext(Dispatchers.Default) {
    val scope = if (showSystemApps) ANDROID_PACKAGE_SCOPE_ALL else ANDROID_PACKAGE_SCOPE_USER
    if (currentUserOnly) {
        return@withContext getCurrentUserPackagesInfo(scope)
            .map(InstalledPackageInfo::toAppPackageEntry)
            .distinctBy { entry -> "${entry.userId}:${entry.uid}:${entry.packageName}" }
            .sortedWith(
                compareBy<AppPackageEntry> { entry -> entry.userId ?: 0 }
                    .thenBy { entry -> entry.name },
            )
    }

    val packageNames = listPackages(scope)
        .normalizedPackageNames()

    if (packageNames.isEmpty()) {
        return@withContext emptyList()
    }

    getPackagesInfo(packageNames)
        .map(InstalledPackageInfo::toAppPackageEntry)
        .distinctBy { entry -> "${entry.userId}:${entry.uid}:${entry.packageName}" }
        .sortedWith(
            compareBy<AppPackageEntry> { entry -> entry.userId ?: 0 }
                .thenBy { entry -> entry.name },
        )
}

internal val AppPackageEntry.name: String
    get() = appLabel?.ifBlank { packageName } ?: packageName

internal fun AndroidUser.toAndroidUserSpace(): AndroidUserSpace {
    return AndroidUserSpace(id = id, name = name)
}

internal suspend fun prepareProxyAppListData(
    userSpaces: List<AndroidUserSpace>,
    appPackages: List<AppPackageEntry>,
    searchValue: String,
    fixedUserId: Int?,
): ProxyAppListPreparedData = withContext(Dispatchers.Default) {
    val appsByUser = appPackages.groupByUserSpace(fixedUserId = fixedUserId)
    val displayedUserSpaces = if (fixedUserId == null) {
        userSpaces.withAppPackageUserSpaces(appsByUser)
    } else {
        userSpaces
    }
    val keyword = searchValue.trim()
    val allItemsByUser = LinkedHashMap<Int, List<ProxyAppListItem>>(appsByUser.size)
    val visibleItemsByUser = if (keyword.isEmpty()) {
        allItemsByUser
    } else {
        LinkedHashMap<Int, List<ProxyAppListItem>>(appsByUser.size)
    }

    appsByUser.forEach { (userId, apps) ->
        val selectionInfo = apps.selectionInfo()
        val allItems = apps.toListItems(
            searchValue = "",
            selectionInfo = selectionInfo,
        )
        allItemsByUser[userId] = allItems
        if (keyword.isNotEmpty()) {
            visibleItemsByUser[userId] = apps.toListItems(
                searchValue = keyword,
                selectionInfo = selectionInfo,
            )
        }
    }

    ProxyAppListPreparedData(
        displayedUserSpaces = displayedUserSpaces,
        allItemsByUser = allItemsByUser,
        visibleItemsByUser = visibleItemsByUser,
    )
}

internal suspend fun ProxyAppListPreparedData.toUserSpaceTabs(
    selectedAppKeys: Set<String>,
): List<ProxyAppListUserSpaceTabUi> = withContext(Dispatchers.Default) {
    displayedUserSpaces.map { user ->
        val userItems = allItemsByUser[user.id].orEmpty()
        user.toUserSpaceTab(
            checkedCount = userItems.count { item ->
                item.selectionKeys.any { key -> key in selectedAppKeys }
            },
        )
    }
}

private fun List<AppPackageEntry>.groupByUserSpace(
    fixedUserId: Int? = null,
): Map<Int, List<AppPackageEntry>> {
    if (fixedUserId == null) {
        return groupBy { entry -> entry.userId ?: 0 }
    }
    return mapOf(
        fixedUserId to map { entry ->
            if (entry.userId == fixedUserId) {
                entry
            } else {
                entry.copy(
                    userId = fixedUserId,
                    uid = entry.uid?.toAndroidAppId(),
                )
            }
        }.distinctBy { entry -> entry.packageName },
    )
}

private fun List<AndroidUserSpace>.withAppPackageUserSpaces(
    appPackagesByUser: Map<Int, List<AppPackageEntry>>,
): List<AndroidUserSpace> {
    val knownUserIds = map { user -> user.id }.toSet()
    val fallbackUsers = appPackagesByUser.keys
        .filterNot { userId -> userId in knownUserIds }
        .sorted()
        .map { userId -> AndroidUserSpace(id = userId, name = "User $userId") }
    return (this + fallbackUsers)
        .distinctBy { user -> user.id }
        .sortedBy { user -> user.id }
}

private fun List<AppPackageEntry>.toListItems(
    searchValue: String,
    selectionInfo: AppSelectionInfo,
): List<ProxyAppListItem> {
    val keyword = searchValue.trim()
    return asSequence()
        .filter { app ->
            keyword.isEmpty() ||
                app.name.contains(keyword, ignoreCase = true) ||
                app.packageName.contains(keyword, ignoreCase = true)
        }
        .map { app ->
            val selectionKeys = app.selectionKeys(selectionInfo)
            ProxyAppListItem(
                key = app.selectionKey(),
                app = app,
                sharedUid = app.uid in selectionInfo.sharedUids,
                selectionKeys = selectionKeys,
            )
        }
        .distinctBy { item -> item.key }
        .toList()
}

internal fun List<AppPackageEntry>.sortedForProxyAppListRefresh(
    selectedAppKeys: Set<String>,
): List<AppPackageEntry> {
    val selectionInfo = selectionInfo()
    return sortedWith(
        compareByDescending<AppPackageEntry> { app ->
            app.selectionKeys(selectionInfo).any { key -> key in selectedAppKeys }
        }
            .thenBy { app -> app.groupSortName(selectionInfo) }
            .thenBy { app -> app.groupSortKey(selectionInfo) }
            .thenBy { app -> app.name.normalizedSortText() }
            .thenBy { app -> app.packageName },
    )
}

private fun List<String>.normalizedPackageNames(): List<String> {
    return map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .sorted()
}

private fun InstalledPackageInfo.toAppPackageEntry(
): AppPackageEntry {
    return AppPackageEntry(
        packageName = packageName,
        appLabel = appLabel.ifBlank { packageName },
        userId = uid.toAndroidUserId(),
        uid = uid,
        system = isSystem,
    )
}

private fun AndroidUserSpace.toUserSpaceTab(
    checkedCount: Int,
): ProxyAppListUserSpaceTabUi {
    return ProxyAppListUserSpaceTabUi(
        id = id,
        label = name.ifBlank { "User $id" },
        checkedCount = checkedCount,
    )
}

private data class AppSelectionInfo(
    val keysByUid: Map<Int, List<String>>,
    val sharedUids: Set<Int>,
    val sortNamesByUid: Map<Int, String>,
)

private fun List<AppPackageEntry>.selectionInfo(): AppSelectionInfo {
    val appsByUid = filter { app -> app.uid != null }
        .groupBy { app -> app.uid ?: 0 }
    return AppSelectionInfo(
        keysByUid = appsByUid.mapValues { (_, apps) ->
            apps.map { app -> app.selectionKey() }.distinct()
        },
        sharedUids = appsByUid
            .filterValues { apps -> apps.size > 1 }
            .keys,
        sortNamesByUid = appsByUid.mapValues { (_, apps) ->
            apps.minOf { app -> app.name.normalizedSortText() }
        },
    )
}

private fun AppPackageEntry.selectionKeys(
    selectionInfo: AppSelectionInfo,
): List<String> {
    val currentUid = uid ?: return listOf(selectionKey())
    return selectionInfo.keysByUid[currentUid] ?: listOf(selectionKey())
}

private fun AppPackageEntry.selectionKey(): String = "${userId ?: 0}:$packageName"

private fun AppPackageEntry.groupSortName(
    selectionInfo: AppSelectionInfo,
): String {
    val currentUid = uid ?: return name.normalizedSortText()
    return selectionInfo.sortNamesByUid[currentUid] ?: name.normalizedSortText()
}

private fun AppPackageEntry.groupSortKey(
    selectionInfo: AppSelectionInfo,
): String {
    val currentUid = uid
    return if (currentUid != null && currentUid in selectionInfo.sharedUids) {
        "uid:$currentUid"
    } else {
        "app:${selectionKey()}"
    }
}

private fun String.normalizedSortText(): String = lowercase()
